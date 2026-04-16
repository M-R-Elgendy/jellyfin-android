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
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.bridge.ExternalPlayer
import org.jellyfin.mobile.bridge.MediaSegments
import org.jellyfin.mobile.bridge.NativeInterface
import org.jellyfin.mobile.bridge.NativePlayer
import org.jellyfin.mobile.player.ui.QueueItem
import org.jellyfin.mobile.player.ui.QueueSheetAdapter
import org.jellyfin.mobile.player.ui.QueueSheetHelper
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.SortOrder
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

class WebViewFragment : Fragment(), BackPressInterceptor, JellyfinWebChromeClient.FileChooserListener {
    val appPreferences: AppPreferences by inject()
    private val apiClientController: ApiClientController by inject()
    private val webappFunctionChannel: WebappFunctionChannel by inject()
    private lateinit var assetsPathHandler: AssetsPathHandler
    private lateinit var jellyfinWebViewClient: JellyfinWebViewClient
    private val nativePlayer: NativePlayer by inject()
    private lateinit var externalPlayer: ExternalPlayer
    private val mediaSegments: MediaSegments by inject()

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
                val items = (0 until itemsArray.length()).map { i ->
                    val obj = itemsArray.getJSONObject(i)
                    QueueItem(
                        itemId = obj.getString("itemId").toUUIDOrNull() ?: return,
                        title = obj.optString("title", ""),
                        seriesName = obj.optString("seriesName", null),
                        duration = kotlin.time.Duration.ZERO.let {
                            val ticks = obj.optLong("runTimeTicks", 0)
                            if (ticks > 0) kotlin.time.Duration.parse("${ticks / 10_000}ms") else it
                        },
                        imageTag = obj.optString("imageTag", null),
                    )
                }
                showQueueItems(items, currentIndex)
            } else {
                val currentItemId = data.optString("currentItemId", "").toUUIDOrNull()
                val parentId = data.optString("parentId", "").toUUIDOrNull()
                Timber.d("QueueSwipeWeb: empty queue, fallback currentItemId=%s, parentId=%s", currentItemId, parentId)

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
        }
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
            try {
                val apiClient: ApiClient by inject()
                val item = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    apiClient.userLibraryApi.getItem(currentItemId).content
                }
                val parentId = item.parentId
                Timber.d("QueueSwipeWeb: fetched item parentId=%s", parentId)
                if (parentId != null) {
                    fetchSiblingItems(parentId, currentItemId)
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
                Timber.e(e, "QueueSwipeWeb: failed to fetch current item")
                showQueueItems(emptyList(), 0)
            }
        }
    }

    private fun fetchSiblingItems(parentId: java.util.UUID, currentItemId: java.util.UUID?) {
        lifecycleScope.launch {
            try {
                val apiClient: ApiClient by inject()
                val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    apiClient.itemsApi.getItems(
                        parentId = parentId,
                        includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO, BaseItemKind.TRAILER),
                        sortBy = listOf(org.jellyfin.sdk.model.api.ItemSortBy.SORT_NAME),
                        sortOrder = listOf(SortOrder.ASCENDING),
                        recursive = true,
                        limit = 100,
                    ).content
                }
                val resultItems = response.items.orEmpty()
                Timber.d("QueueSwipeWeb: fetched %d sibling items from parentId=%s", resultItems.size, parentId)

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
            } catch (e: Exception) {
                Timber.e(e, "QueueSwipeWeb: failed to fetch sibling items")
                showQueueItems(emptyList(), 0)
            }
        }
    }

    private fun setupQueueSheet() {
        val binding = webViewBinding ?: return
        val queueSheetRoot = binding.root.findViewById<View>(R.id.queue_sheet_root) ?: return
        val apiClient: ApiClient by inject()

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
        private const val SWIPE_UP_MIN_DISTANCE = 100f
        private const val SWIPE_UP_MAX_DRIFT = 400f
        private const val SWIPE_DEBOUNCE_MS = 300L
    }
}
