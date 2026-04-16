package org.jellyfin.mobile.webapp

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.bridge.ExternalPlayer
import org.jellyfin.mobile.bridge.MediaSegments
import org.jellyfin.mobile.bridge.NativeInterface
import org.jellyfin.mobile.bridge.NativePlayer
import org.jellyfin.mobile.player.ui.QueueItem
import org.jellyfin.mobile.player.ui.QueueSheetAdapter
import org.jellyfin.mobile.player.ui.QueueSheetHelper
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetEpisodesRequest
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.json.JSONObject
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.databinding.FragmentWebviewBinding
import org.jellyfin.mobile.setup.ConnectFragment
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.BackPressInterceptor
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.Constants.FRAGMENT_WEB_VIEW_EXTRA_SERVER
import org.jellyfin.mobile.utils.applyDefault
import org.jellyfin.mobile.utils.applyWindowInsetsAsMargins
import org.jellyfin.mobile.utils.dip
import org.jellyfin.mobile.utils.extensions.getParcelableCompat
import org.jellyfin.mobile.utils.extensions.replaceFragment
import org.jellyfin.mobile.utils.fadeIn
import org.jellyfin.mobile.utils.isOutdated
import org.jellyfin.mobile.utils.requestNoBatteryOptimizations
import org.jellyfin.mobile.utils.runOnUiThread
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.UUID

class WebViewFragment : Fragment(), BackPressInterceptor, JellyfinWebChromeClient.FileChooserListener {
    val appPreferences: AppPreferences by inject()
    private val apiClientController: ApiClientController by inject()
    private val webappFunctionChannel: WebappFunctionChannel by inject()
    private lateinit var assetsPathHandler: AssetsPathHandler
    private lateinit var jellyfinWebViewClient: JellyfinWebViewClient
    private val nativePlayer: NativePlayer by inject()
    private lateinit var externalPlayer: ExternalPlayer
    private val mediaSegments: MediaSegments by inject()
    private val apiClient: ApiClient by inject()
    private val userDao: UserDao by inject()

    lateinit var server: ServerEntity
        private set
    private var connected = false
    private val timeoutRunnable = Runnable {
        handleError()
    }
    private val showLoadingContainerRunnable = Runnable {
        webViewBinding?.loadingContainer?.isVisible = true
    }

    // UI
    private var webViewBinding: FragmentWebviewBinding? = null
    private var queueSheetHelper: QueueSheetHelper? = null
    private var queueSheetAdapter: QueueSheetAdapter? = null
    private var isWebFullscreen = false
    private var swipeStartY = 0f
    private var swipeStartX = 0f
    private var lastSwipeToggleTime = 0L

    // External file access
    private var fileChooserActivityLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        fileChooserCallback?.onReceiveValue(FileChooserParams.parseResult(result.resultCode, result.data))
    }
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = requireNotNull(requireArguments().getParcelableCompat(FRAGMENT_WEB_VIEW_EXTRA_SERVER)) {
            "Server entity has not been supplied!"
        }

        assetsPathHandler = AssetsPathHandler(requireContext())
        jellyfinWebViewClient = object : JellyfinWebViewClient(
            lifecycleScope,
            server,
            assetsPathHandler,
            apiClientController,
        ) {
            override fun onConnectedToWebapp() {
                val webViewBinding = webViewBinding ?: return
                val webView = webViewBinding.webView
                webView.removeCallbacks(timeoutRunnable)
                webView.removeCallbacks(showLoadingContainerRunnable)
                connected = true
                runOnUiThread {
                    webViewBinding.loadingContainer.isVisible = false
                    webView.fadeIn()
                }
                requestNoBatteryOptimizations(webViewBinding.root)
            }

            override fun onErrorReceived() {
                handleError()
            }
        }
        externalPlayer = ExternalPlayer(requireContext(), this, requireActivity().activityResultRegistry)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentWebviewBinding.inflate(inflater, container, false).also { binding ->
            webViewBinding = binding
        }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val webView = webViewBinding!!.webView

        // Apply window insets
        webView.applyWindowInsetsAsMargins()

        // Setup exclusion rects for gestures
        if (AndroidVersion.isAtLeastQ) {
            @Suppress("MagicNumber")
            webView.doOnNextLayout {
                // Maximum allowed exclusion rect height is 200dp,
                // offsetting 100dp from the center in both directions
                // uses the maximum available space
                val verticalCenter = webView.measuredHeight / 2
                val offset = webView.resources.dip(100)

                // Arbitrary, currently 2x minimum touch target size
                val exclusionWidth = webView.resources.dip(96)

                webView.systemGestureExclusionRects = listOf(
                    Rect(
                        0,
                        verticalCenter - offset,
                        exclusionWidth,
                        verticalCenter + offset,
                    ),
                )
            }
        }

        // Setup WebView
        webView.initialize()

        webViewBinding!!.useDifferentServerButton.setOnClickListener {
            webView.removeCallbacks(timeoutRunnable)
            webView.stopLoading()
            webViewBinding!!.loadingContainer.isVisible = false
            onSelectServer(error = false)
        }

        setupQueueSheet()
        setupSwipeDetection()

        webViewBinding!!.rotateScreenButton.setOnClickListener {
            val activity = activity ?: return@setOnClickListener
            val current = resources.configuration.orientation
            activity.requestedOrientation = if (current == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }

        // Process JS functions called from other components (e.g. the PlayerActivity)
        lifecycleScope.launch {
            for (function in webappFunctionChannel) {
                webView.loadUrl("javascript:$function")
            }
        }
    }

    override fun onInterceptBackPressed(): Boolean {
        if (queueSheetHelper?.isOpen == true) {
            queueSheetHelper?.close()
            return true
        }
        return connected && webappFunctionChannel.goBack()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        webViewBinding = null
        queueSheetHelper = null
        queueSheetAdapter = null
    }

    fun onWebFullscreenChanged(isFullscreen: Boolean) {
        isWebFullscreen = isFullscreen
        Timber.d("QueueSwipeWeb: onWebFullscreenChanged(%b)", isFullscreen)
        webViewBinding?.rotateScreenButton?.isVisible = isFullscreen
        webViewBinding?.root?.findViewById<View>(R.id.swipe_detection_zone)?.isVisible = isFullscreen
        if (!isFullscreen) {
            queueSheetHelper?.close()
        }
    }

    fun onQueueDataReceived(json: String) {
        Timber.d("QueueSwipeWeb: onQueueDataReceived, json=%s", json.take(500))
        try {
            val data = JSONObject(json)
            val itemsArray = data.optJSONArray("items") ?: return
            val currentIndex = data.optInt("currentIndex", 0)

            if (itemsArray.length() > 0) {
                val items = buildList {
                    for (i in 0 until itemsArray.length()) {
                        val obj = itemsArray.getJSONObject(i)
                        val itemId = obj.optString("itemId", "").toUUIDOrNull() ?: continue
                        add(
                            QueueItem(
                                itemId = itemId,
                                title = obj.optString("title", ""),
                                seriesName = obj.optString("seriesName", "").takeIf { it.isNotBlank() },
                                duration = kotlin.time.Duration.ZERO.let {
                                    val ticks = obj.optLong("runTimeTicks", 0)
                                    if (ticks > 0) kotlin.time.Duration.parse("${ticks / 10_000}ms") else it
                                },
                                imageTag = obj.optString("imageTag", "").takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                }
                if (items.isNotEmpty()) {
                    val safeIndex = currentIndex.coerceIn(0, items.lastIndex)
                    Timber.d(
                        "QueueSwipeWeb API: (no HTTP) queue from WebView JS | items=%d currentIndex=%d",
                        items.size,
                        safeIndex,
                    )
                    showQueueItems(items, safeIndex)
                } else {
                    Timber.d("QueueSwipeWeb: playlist JSON had %d rows but no valid itemIds", itemsArray.length())
                    showQueueItems(emptyList(), 0)
                }
            } else {
                val rawCurrentItemId = data.optString("currentItemId", "").trim()
                val rawParentId = data.optString("parentId", "").trim()
                val currentItemId = rawCurrentItemId.toUUIDOrNull()
                val parentId = rawParentId.toUUIDOrNull()
                Timber.d("QueueSwipeWeb: empty queue, fallback currentItemId=%s, parentId=%s", currentItemId, parentId)
                if (rawCurrentItemId.isNotEmpty() && currentItemId == null) {
                    Timber.w(
                        "QueueSwipeWeb HTTP: no Jellyfin request — currentItemId is not a UUID (raw=\"%s\"). " +
                            "Native fallback needs a real Item Id (GUID).",
                        rawCurrentItemId,
                    )
                }
                if (rawParentId.isNotEmpty() && parentId == null) {
                    Timber.w(
                        "QueueSwipeWeb HTTP: no Jellyfin request — parentId is not a UUID (raw=\"%s\").",
                        rawParentId,
                    )
                }
                if (rawCurrentItemId.isEmpty() && rawParentId.isEmpty()) {
                    Timber.w(
                        "QueueSwipeWeb HTTP: no Jellyfin request — JS payload has no fallback ids " +
                            "(currentItemId/parentId are both empty).",
                    )
                }

                if (parentId != null) {
                    fetchSiblingItems(parentId, currentItemId)
                } else if (currentItemId != null) {
                    fetchCurrentItemAndSiblings(currentItemId)
                } else {
                    showQueueItems(emptyList(), 0)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "QueueSwipeWeb: error parsing queue data")
            showQueueItems(emptyList(), 0)
        }
    }

    /**
     * Logs the same GET URL the Jellyfin Kotlin SDK would call, plus [Authorization] and [Accept] headers.
     * Warning: [Authorization] includes the access token; visible in logcat (debug only).
     */
    private fun logQueueJellyfinHttpCall(
        operationName: String,
        pathTemplate: String,
        pathParameters: Map<String, Any?> = emptyMap(),
        queryParameters: Map<String, Any?> = emptyMap(),
    ) {
        val baseUrl = apiClient.baseUrl
        if (baseUrl.isNullOrBlank()) {
            Timber.w("QueueSwipeWeb HTTP: %s skipped — baseUrl is null", operationName)
            return
        }
        val fullUrl = runCatching {
            apiClient.createUrl(pathTemplate, pathParameters, queryParameters)
        }.getOrElse { e ->
            "$baseUrl$pathTemplate (createUrl failed: ${e.message})"
        }
        val authorization = AuthorizationHeaderBuilder.buildHeader(
            clientName = apiClient.clientInfo.name,
            clientVersion = apiClient.clientInfo.version,
            deviceId = apiClient.deviceInfo.id,
            deviceName = apiClient.deviceInfo.name,
            accessToken = apiClient.accessToken,
        )
        Timber.d(
            "QueueSwipeWeb HTTP: GET %s | URL=%s | Authorization=%s | Accept=%s",
            operationName,
            fullUrl,
            authorization,
            ApiClient.HEADER_ACCEPT,
        )
    }

    private fun showQueueItems(items: List<QueueItem>, currentIndex: Int) {
        queueSheetAdapter?.submitList(items)
        queueSheetAdapter?.currentIndex = currentIndex
        queueSheetHelper?.updateQueueState(items.isNotEmpty())
        if (queueSheetHelper?.isOpen != true) {
            queueSheetHelper?.open(currentIndex)
        }
    }

    private fun fetchCurrentItemAndSiblings(currentItemId: java.util.UUID) {
        lifecycleScope.launch {
            val queuedUserId = queryQueueUserId()
            logQueueJellyfinHttpCall(
                operationName = "GetItem",
                pathTemplate = "/Items/{itemId}",
                pathParameters = mapOf("itemId" to currentItemId),
                queryParameters = buildMap { queuedUserId?.let { put("userId", it) } },
            )
            try {
                val item = withContext(Dispatchers.IO) {
                    apiClient.userLibraryApi.getItem(currentItemId, queuedUserId).content
                }
                val parentId = item.parentId
                Timber.d("QueueSwipeWeb: fetched item parentId=%s", parentId)
                if (parentId != null) {
                    loadQueueFromParentOrAdjacent(parentId, currentItemId, queuedUserId)
                } else {
                    val singleItem = QueueItem(
                        itemId = currentItemId,
                        title = item.name.orEmpty(),
                        seriesName = item.seriesName,
                        duration = item.runTimeTicks?.ticks ?: kotlin.time.Duration.ZERO,
                        imageTag = item.imageTags?.get(ImageType.PRIMARY),
                    )
                    showQueueItems(listOf(singleItem), 0)
                }
            } catch (e: Exception) {
                Timber.e(e, "QueueSwipeWeb: failed to fetch current item, trying adjacentTo")
                try {
                    loadQueueFromParentOrAdjacent(null, currentItemId, queuedUserId)
                } catch (e2: Exception) {
                    Timber.e(e2, "QueueSwipeWeb: adjacent fallback failed")
                    showQueueItems(emptyList(), 0)
                }
            }
        }
    }

    private fun fetchSiblingItems(parentId: java.util.UUID, currentItemId: java.util.UUID?) {
        lifecycleScope.launch {
            try {
                val queuedUserId = queryQueueUserId()
                loadQueueFromParentOrAdjacent(parentId, currentItemId, queuedUserId)
            } catch (e: Exception) {
                Timber.e(e, "QueueSwipeWeb: failed to fetch sibling items")
                showQueueItems(emptyList(), 0)
            }
        }
    }

    /**
     * Resolves the signed-in Jellyfin user id (UUID) for library-scoped queries.
     * OpenAPI: optional `userId` query on [GetItems](https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json)
     * (`GET /Items`) and [GetItem](https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json) (`GET /Items/{itemId}`).
     */
    private suspend fun queryQueueUserId(): UUID? = withContext(Dispatchers.IO) {
        val serverId = appPreferences.currentServerId ?: return@withContext null
        val userPk = appPreferences.currentUserId ?: return@withContext null
        val uuid = userDao.getServerUser(serverId, userPk)?.user?.userId?.toUUIDOrNull()
        Timber.d(
            "QueueSwipeWeb API: (local Room) resolve Jellyfin userId for query param | userId=%s",
            uuid ?: "null",
        )
        uuid
    }

    private suspend fun resolveParentKind(parentId: UUID, queuedUserId: UUID?): BaseItemKind? =
        withContext(Dispatchers.IO) {
            logQueueJellyfinHttpCall(
                operationName = "GetItem (resolve parent type)",
                pathTemplate = "/Items/{itemId}",
                pathParameters = mapOf("itemId" to parentId),
                queryParameters = buildMap { queuedUserId?.let { put("userId", it) } },
            )
            runCatching {
                apiClient.userLibraryApi.getItem(parentId, queuedUserId).content.type
            }.getOrNull()
        }

    /**
     * Loads children of [parentId] (several query shapes), then falls back to `adjacentTo` when the
     * parent query returns nothing — e.g. some playlist / web client combinations.
     *
     * Server contract: Jellyfin stable OpenAPI — [GetItems](https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json)
     * (`GET /Items`, `operationId` GetItems) with `parentId`, `recursive`, `includeItemTypes`, `adjacentTo`, `userId`;
     * for a **Series** parent, [GetEpisodes](https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json)
     * (`GET /Shows/{seriesId}/Episodes`) via [org.jellyfin.sdk.api.operations.TvShowsApi.getEpisodes].
     */
    private suspend fun loadQueueFromParentOrAdjacent(
        parentId: java.util.UUID?,
        currentItemId: java.util.UUID?,
        queuedUserId: UUID?,
    ) {
        Timber.d(
            "QueueSwipeWeb API: loadQueueFromParentOrAdjacent | parentId=%s currentItemId=%s userId=%s",
            parentId ?: "null",
            currentItemId ?: "null",
            queuedUserId ?: "null",
        )
        val fromParent = if (parentId != null) querySiblingDtos(parentId, queuedUserId) else emptyList()
        val adjacent = if (fromParent.isEmpty() && currentItemId != null) {
            Timber.d("QueueSwipeWeb: parent query returned 0, trying adjacentTo=%s", currentItemId)
            queryAdjacentDtos(currentItemId, queuedUserId)
        } else {
            emptyList()
        }
        val resultItems = fromParent.ifEmpty { adjacent }
        Timber.d(
            "QueueSwipeWeb: showing %d items (parent=%d, adjacent=%d)",
            resultItems.size,
            fromParent.size,
            adjacent.size,
        )
        showQueueFromDtos(resultItems, currentItemId)
    }

    private suspend fun querySiblingDtos(parentId: java.util.UUID, queuedUserId: UUID?): List<BaseItemDto> {
        val parentKind = resolveParentKind(parentId, queuedUserId)

        if (parentKind == BaseItemKind.SERIES && queuedUserId != null) {
            logQueueJellyfinHttpCall(
                operationName = "GetEpisodes",
                pathTemplate = "/Shows/{seriesId}/Episodes",
                pathParameters = mapOf("seriesId" to parentId),
                queryParameters = buildMap {
                    put("userId", queuedUserId)
                    put("limit", QUEUE_EPISODES_FROM_SERIES_LIMIT)
                    put("sortBy", ItemSortBy.INDEX_NUMBER)
                },
            )
            val fromSeries = withContext(Dispatchers.IO) {
                apiClient.tvShowsApi.getEpisodes(
                    GetEpisodesRequest(
                        seriesId = parentId,
                        userId = queuedUserId,
                        limit = QUEUE_EPISODES_FROM_SERIES_LIMIT,
                        sortBy = ItemSortBy.INDEX_NUMBER,
                    ),
                ).content.items.orEmpty()
            }
            val seriesFiltered = fromSeries.filter { it.type in QUEUE_MEDIA_KINDS }.ifEmpty { fromSeries }
            if (seriesFiltered.isNotEmpty()) return seriesFiltered
        }

        val sortBy = when (parentKind) {
            BaseItemKind.SEASON -> listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER)
            else -> listOf(ItemSortBy.SORT_NAME)
        }

        val typedKinds = QUEUE_MEDIA_KINDS.toList()
        suspend fun runQuery(recursive: Boolean, kinds: Collection<BaseItemKind>?): List<BaseItemDto> =
            withContext(Dispatchers.IO) {
                val queryParameters = buildMap<String, Any?> {
                    queuedUserId?.let { put("userId", it) }
                    put("parentId", parentId)
                    put("recursive", recursive)
                    put("limit", 100)
                    put("sortBy", sortBy)
                    put("sortOrder", listOf(SortOrder.ASCENDING))
                    if (kinds != null) put("includeItemTypes", kinds)
                }
                logQueueJellyfinHttpCall(
                    operationName = "GetItems",
                    pathTemplate = "/Items",
                    queryParameters = queryParameters,
                )
                apiClient.itemsApi.getItems(
                    userId = queuedUserId,
                    parentId = parentId,
                    includeItemTypes = kinds ?: emptyList(),
                    sortBy = sortBy,
                    sortOrder = listOf(SortOrder.ASCENDING),
                    recursive = recursive,
                    limit = 100,
                ).content.items.orEmpty()
            }

        var items = runQuery(recursive = false, kinds = typedKinds)
        if (items.isEmpty()) items = runQuery(recursive = true, kinds = typedKinds)
        if (items.isEmpty()) items = runQuery(recursive = false, kinds = null)
        if (items.isEmpty()) items = runQuery(recursive = true, kinds = null)
        return items.filter { it.type in QUEUE_MEDIA_KINDS }.ifEmpty { items }
    }

    private suspend fun queryAdjacentDtos(currentItemId: java.util.UUID, queuedUserId: UUID?): List<BaseItemDto> {
        val raw = withContext(Dispatchers.IO) {
            logQueueJellyfinHttpCall(
                operationName = "GetItems (adjacentTo)",
                pathTemplate = "/Items",
                queryParameters = buildMap {
                    queuedUserId?.let { put("userId", it) }
                    put("adjacentTo", currentItemId)
                    put("sortBy", listOf(ItemSortBy.SORT_NAME))
                    put("sortOrder", listOf(SortOrder.ASCENDING))
                    put("limit", 100)
                },
            )
            apiClient.itemsApi.getItems(
                userId = queuedUserId,
                adjacentTo = currentItemId,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                limit = 100,
            ).content.items.orEmpty()
        }
        return raw.filter { it.type in QUEUE_MEDIA_KINDS }.ifEmpty { raw }
    }

    private fun showQueueFromDtos(resultItems: List<BaseItemDto>, currentItemId: java.util.UUID?) {
        var currentIndex = 0
        val items = resultItems.mapIndexed { index, dto ->
            if (dto.id == currentItemId) currentIndex = index
            QueueItem(
                itemId = dto.id,
                title = dto.name.orEmpty(),
                seriesName = dto.seriesName,
                duration = dto.runTimeTicks?.ticks ?: kotlin.time.Duration.ZERO,
                imageTag = dto.imageTags?.get(ImageType.PRIMARY),
            )
        }
        showQueueItems(items, currentIndex)
    }

    private fun setupQueueSheet() {
        val binding = webViewBinding ?: return
        val queueSheetRoot = binding.root.findViewById<View>(R.id.queue_sheet_root) ?: return

        queueSheetAdapter = QueueSheetAdapter(apiClient) { index ->
            val items = queueSheetAdapter?.currentList ?: return@QueueSheetAdapter
            val item = items.getOrNull(index) ?: return@QueueSheetAdapter
            webappFunctionChannel.selectQueueItem(item.itemId.toString())
            queueSheetHelper?.close()
        }
        val recyclerView = queueSheetRoot.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.queue_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = queueSheetAdapter

        queueSheetHelper = QueueSheetHelper(queueSheetRoot) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSwipeDetection() {
        val swipeZone = webViewBinding?.root?.findViewById<View>(R.id.swipe_detection_zone) ?: return
        Timber.d("QueueSwipeWeb: setupSwipeDetection, swipeZone=%s, visible=%b", swipeZone, swipeZone.isVisible)
        swipeZone.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = event.rawX
                    swipeStartY = event.rawY
                    Timber.d("QueueSwipeWeb: ACTION_DOWN at (%.0f, %.0f)", event.rawX, event.rawY)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    Timber.d("QueueSwipeWeb: ACTION_UP at (%.0f, %.0f), isLandscape=%b, isWebFullscreen=%b", event.rawX, event.rawY, isLandscape, isWebFullscreen)
                    if (isLandscape && isWebFullscreen) {
                        val deltaY = swipeStartY - event.rawY
                        val deltaX = kotlin.math.abs(swipeStartX - event.rawX)
                        val now = System.currentTimeMillis()
                        Timber.d("QueueSwipeWeb: deltaY=%.0f, deltaX=%.0f (need >=%.0f, <=%.0f)", deltaY, deltaX, SWIPE_UP_MIN_DISTANCE, SWIPE_UP_MAX_DRIFT)
                        if (deltaY >= SWIPE_UP_MIN_DISTANCE && deltaX <= SWIPE_UP_MAX_DRIFT && now - lastSwipeToggleTime > SWIPE_DEBOUNCE_MS) {
                            lastSwipeToggleTime = now
                            Timber.d("QueueSwipeWeb: VALID swipe-up, requesting queue data")
                            webappFunctionChannel.requestQueueData()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun WebView.initialize() {
        if (!appPreferences.ignoreWebViewChecks && isOutdated()) { // Check WebView version
            showOutdatedWebViewDialog(this)
            return
        }
        webViewClient = jellyfinWebViewClient
        webChromeClient = JellyfinWebChromeClient(this@WebViewFragment)
        settings.applyDefault()
        addJavascriptInterface(NativeInterface(requireContext()), "NativeInterface")
        addJavascriptInterface(nativePlayer, "NativePlayer")
        addJavascriptInterface(externalPlayer, "ExternalPlayer")
        addJavascriptInterface(mediaSegments, "MediaSegments")

        loadUrl(server.hostname)
        postDelayed(timeoutRunnable, Constants.INITIAL_CONNECTION_TIMEOUT)
        postDelayed(showLoadingContainerRunnable, Constants.SHOW_PROGRESS_BAR_DELAY)
    }

    private fun showOutdatedWebViewDialog(webView: WebView) {
        AlertDialog.Builder(requireContext()).apply {
            setTitle(R.string.dialog_web_view_outdated)
            setMessage(R.string.dialog_web_view_outdated_message)
            setCancelable(false)

            val webViewPackage = WebViewCompat.getCurrentWebViewPackage(context)
            if (webViewPackage != null) {
                val marketUri = Uri.Builder().apply {
                    scheme("market")
                    authority("details")
                    appendQueryParameter("id", webViewPackage.packageName)
                }.build()
                val referrerUri = Uri.Builder().apply {
                    scheme("android-app")
                    authority(context.packageName)
                }.build()

                val marketIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = marketUri
                    putExtra(Intent.EXTRA_REFERRER, referrerUri)
                }

                // Only show button if the intent can be resolved
                if (marketIntent.resolveActivity(context.packageManager) != null) {
                    setNegativeButton(R.string.dialog_button_check_for_updates) { _, _ ->
                        startActivity(marketIntent)
                        requireActivity().finishAfterTransition()
                    }
                }
            }
            if (AndroidVersion.isAtLeastN) {
                setPositiveButton(R.string.dialog_button_open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_WEBVIEW_SETTINGS))
                    Toast.makeText(context, R.string.toast_reopen_after_change, Toast.LENGTH_LONG).show()
                    requireActivity().finishAfterTransition()
                }
            }
            setNeutralButton(R.string.dialog_button_ignore) { _, _ ->
                appPreferences.ignoreWebViewChecks = true
                // Re-initialize
                webView.initialize()
            }
        }.show()
    }

    private fun onSelectServer(error: Boolean = false) = runOnUiThread {
        val activity = activity
        if (activity != null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val extras = when {
                error -> Bundle().apply {
                    putBoolean(Constants.FRAGMENT_CONNECT_EXTRA_ERROR, true)
                }
                else -> null
            }
            parentFragmentManager.replaceFragment<ConnectFragment>(extras)
        }
    }

    private fun handleError() {
        connected = false
        onSelectServer(error = true)
    }

    override fun onShowFileChooser(intent: Intent, filePathCallback: ValueCallback<Array<Uri>>) {
        fileChooserCallback = filePathCallback
        fileChooserActivityLauncher.launch(intent)
    }

    companion object {
        private val QUEUE_MEDIA_KINDS = setOf(
            BaseItemKind.MOVIE,
            BaseItemKind.EPISODE,
            BaseItemKind.VIDEO,
            BaseItemKind.MUSIC_VIDEO,
            BaseItemKind.TRAILER,
            BaseItemKind.AUDIO,
            BaseItemKind.RECORDING,
        )

        private const val SWIPE_UP_MIN_DISTANCE = 100f
        private const val SWIPE_UP_MAX_DRIFT = 400f
        private const val SWIPE_DEBOUNCE_MS = 300L

        /** Cap for [GetEpisodes](https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json) when parent is a Series. */
        private const val QUEUE_EPISODES_FROM_SERIES_LIMIT = 200
    }
}
