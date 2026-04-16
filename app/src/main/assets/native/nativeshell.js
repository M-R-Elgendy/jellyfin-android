const features = [
    "castmenuhashchange",
    "clientsettings",
    "displaylanguage",
    "downloadmanagement",
    "exit",
    "externallinks",
    "filedownload",
    "fileinput",
    "htmlaudioautoplay",
    "htmlvideoautoplay",
    "multiserver",
    "physicalvolumecontrol",
    "remotecontrol",
    "subtitleappearancesettings",
    "subtitleburnsettings"
];

const plugins = [
    'NavigationPlugin',
    'ExoPlayerPlugin',
    'ExternalPlayerPlugin',
    'MediaSegmentsPlugin'
];

// Add plugin loaders
for (const plugin of plugins) {
    window[plugin] = async () => {
        const pluginDefinition = await import(`/native/${plugin}.js`);
        return pluginDefinition[plugin];
    };
}

const { deviceId, deviceName, appName, appVersion } = JSON.parse(window.NativeInterface.getDeviceInformation());

window.NativeShell = {
    enableFullscreen() {
        window.NativeInterface.enableFullscreen();
    },

    disableFullscreen() {
        window.NativeInterface.disableFullscreen();
    },

    openUrl(url, target) {
        window.NativeInterface.openUrl(url);
    },

    updateMediaSession(mediaInfo) {
        window.NativeInterface.updateMediaSession(JSON.stringify(mediaInfo));
    },

    hideMediaSession() {
        window.NativeInterface.hideMediaSession();
    },

    updateVolumeLevel(value) {
        window.NativeInterface.updateVolumeLevel(value);
    },

    downloadFile(downloadInfo) {
        window.NativeInterface.downloadFiles(JSON.stringify([downloadInfo]));
    },

    downloadFiles(downloadInfo) {
        window.NativeInterface.downloadFiles(JSON.stringify(downloadInfo));
    },

    openDownloadManager() {
        window.NativeInterface.openDownloadManager();
    },

    openClientSettings() {
        window.NativeInterface.openClientSettings();
    },

    openDownloads() {
        window.NativeInterface.openDownloads();
    },

    selectServer() {
        window.NativeInterface.openServerSelection();
    },

    getPlugins() {
        return plugins;
    },

    _queueSelectToken: 0,
    _queueSelectAutoplayTimer: null,

    _cancelQueueSelectAutoplay() {
        if (this._queueSelectAutoplayTimer != null) {
            clearInterval(this._queueSelectAutoplayTimer);
            this._queueSelectAutoplayTimer = null;
        }
    },

    _findPlayButton() {
        const selectors = [
            'button[data-action="play"]',
            'button[data-action="resume"]',
            '[data-id="play"]',
            '[data-id="resume"]',
            '.btnPlay',
            '.btnResume',
            '.detailButton-play',
            '.detailButton-primary',
            '.playButton',
            '.resumeButton',
            'button.playbackButton',
        ];
        for (const selector of selectors) {
            const el = document.querySelector(selector);
            if (el && !el.disabled && el.offsetParent !== null) {
                return el;
            }
        }
        const clickable = Array.from(document.querySelectorAll('button, a, [role="button"], .button-flat'));
        return clickable.find((el) => {
            if (!el || el.disabled || el.offsetParent === null) return false;
            const txt = String(el.textContent || '').trim().toLowerCase();
            return txt === 'play' || txt === 'resume' || txt.startsWith('play ');
        }) || null;
    },

    _navigateToDetails(itemId) {
        const route = `details?id=${itemId}`;
        if (window.NavigationHelper && typeof window.NavigationHelper.navigate === 'function') {
            window.NavigationHelper.navigate(route);
            return true;
        }
        if (window.location) {
            window.location.hash = `#!/${route}`;
            return true;
        }
        return false;
    },

    async _autoPlayFromDetails(itemId, token) {
        this._cancelQueueSelectAutoplay();
        const startedAt = Date.now();
        const timeoutMs = 7000;
        const pollMs = 200;
        return new Promise((resolve) => {
            this._queueSelectAutoplayTimer = setInterval(() => {
                if (token !== this._queueSelectToken) {
                    this._cancelQueueSelectAutoplay();
                    console.warn('[QueueSelect] autoplay canceled by newer tap');
                    resolve(false);
                    return;
                }
                if (Date.now() - startedAt > timeoutMs) {
                    this._cancelQueueSelectAutoplay();
                    console.warn('[QueueSelect] autoplay timeout on details page');
                    resolve(false);
                    return;
                }
                const href = String(window.location && window.location.href || '');
                if (!href.includes('details') || !href.includes(itemId)) {
                    return;
                }
                const playBtn = this._findPlayButton();
                if (!playBtn) return;
                this._cancelQueueSelectAutoplay();
                try {
                    playBtn.click();
                    console.log('[QueueSelect] autoplay click succeeded on details page');
                    resolve(true);
                } catch (e) {
                    console.warn('[QueueSelect] autoplay click failed', e);
                    resolve(false);
                }
            }, pollMs);
        });
    },

    async selectQueueItem(itemId) {
        const token = ++this._queueSelectToken;
        try {
            const pm = (window.NavigationHelper && window.NavigationHelper.playbackManager)
                || (window.Emby && window.Emby.PlaybackManager)
                || null;
            if (!pm) {
                console.warn('[QueueSelect] playbackManager missing');
                return false;
            }

            const readMember = (obj, name) => {
                if (!obj || !name) return null;
                const v = obj[name];
                if (typeof v === 'function') {
                    try {
                        return v.call(obj);
                    } catch (e) {
                        console.warn('[QueueSelect] call failed', name, e);
                        return null;
                    }
                }
                return v != null ? v : null;
            };

            const uuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
            const firstUuid = (value) => {
                if (typeof value !== 'string') return null;
                const m = value.match(uuidRe);
                return m ? m[0] : null;
            };
            const itemIdFromAny = (o) => {
                if (!o || typeof o !== 'object') return null;
                return o.Id || o.id || o.ItemId || o.itemId || o.PrimaryItemId || o.primaryItemId || null;
            };
            const currentPlaybackItemId = () => {
                const fromPm = itemIdFromAny(readMember(pm, 'currentItem'))
                    || itemIdFromAny(readMember(pm, 'getCurrentItem'));
                if (typeof fromPm === 'string' && uuidRe.test(fromPm)) return fromPm;

                const p = readMember(pm, 'getCurrentPlayer');
                const fromPlayer = itemIdFromAny(p)
                    || itemIdFromAny(readMember(p, 'item'))
                    || itemIdFromAny(readMember(p, 'currentItem'))
                    || itemIdFromAny(readMember(p, 'getCurrentItem'));
                if (typeof fromPlayer === 'string' && uuidRe.test(fromPlayer)) return fromPlayer;

                const srcCandidates = [
                    readMember(p, 'currentSrc'),
                    p && p.currentSrc,
                    readMember(p, 'src'),
                    p && p.src,
                    readMember(p, 'url'),
                    p && p.url,
                    String(window.location && window.location.href || ''),
                ];
                for (const s of srcCandidates) {
                    const found = firstUuid(s);
                    if (found) return found;
                }
                return null;
            };
            const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

            let playlist = readMember(pm, 'getPlaylist')
                || readMember(pm, 'getCurrentPlaylistItems')
                || readMember(pm, 'playlist');
            if (!Array.isArray(playlist) && playlist) {
                try {
                    playlist = Array.from(playlist);
                } catch (_) {
                    playlist = null;
                }
            }
            if (!Array.isArray(playlist)) playlist = [];

            const target = playlist.find((row) =>
                row && (row.Id === itemId || row.ItemId === itemId || row.id === itemId || row.PlaylistItemId === itemId),
            ) || null;
            const playlistItemId = target && (target.PlaylistItemId || target.Id || target.ItemId || target.id);
            const targetItemId = target && (target.Id || target.ItemId || target.id) || itemId;

            const resolveServerId = () => {
                // 1) Try active runtime objects first.
                const player = readMember(pm, 'getCurrentPlayer');
                const direct = [
                    readMember(pm, 'serverId'),
                    readMember(pm, 'ServerId'),
                    pm && pm.serverId,
                    pm && pm.ServerId,
                    readMember(player, 'serverId'),
                    readMember(player, 'ServerId'),
                    player && player.serverId,
                    player && player.ServerId,
                ].find((v) => typeof v === 'string' && v.length > 0);
                if (direct) return direct;

                // 2) Try stored web credentials (same source used in JellyfinWebViewClient).
                try {
                    const raw = window.localStorage && window.localStorage.getItem('jellyfin_credentials');
                    if (raw) {
                        const creds = JSON.parse(raw);
                        const servers = Array.isArray(creds && creds.Servers) ? creds.Servers : [];
                        const origin = String(window.location && window.location.origin || '').toLowerCase();
                        const matched = servers.find((s) => {
                            const addresses = [
                                s && s.Address,
                                s && s.ManualAddress,
                                s && s.LocalAddress,
                            ].filter((a) => typeof a === 'string').map((a) => a.toLowerCase());
                            return addresses.some((a) => origin && a && (origin.startsWith(a) || a.startsWith(origin)));
                        }) || servers[0] || null;
                        const sid = matched && (matched.Id || matched.ServerId || matched.serverId) || null;
                        if (typeof sid === 'string' && sid.length > 0) return sid;
                    }
                } catch (e) {
                    console.warn('[QueueSelect] resolveServerId from credentials failed', e);
                }
                return null;
            };
            const resolveUserId = () => {
                const player = readMember(pm, 'getCurrentPlayer');
                const direct = [
                    readMember(pm, 'userId'),
                    readMember(pm, 'UserId'),
                    pm && pm.userId,
                    pm && pm.UserId,
                    readMember(player, 'userId'),
                    readMember(player, 'UserId'),
                    player && player.userId,
                    player && player.UserId,
                ].find((v) => typeof v === 'string' && v.length > 0);
                if (direct) return direct;
                try {
                    const raw = window.localStorage && window.localStorage.getItem('jellyfin_credentials');
                    if (raw) {
                        const creds = JSON.parse(raw);
                        const servers = Array.isArray(creds && creds.Servers) ? creds.Servers : [];
                        const origin = String(window.location && window.location.origin || '').toLowerCase();
                        const matched = servers.find((s) => {
                            const addresses = [
                                s && s.Address,
                                s && s.ManualAddress,
                                s && s.LocalAddress,
                            ].filter((a) => typeof a === 'string').map((a) => a.toLowerCase());
                            return addresses.some((a) => origin && a && (origin.startsWith(a) || a.startsWith(origin)));
                        }) || servers[0] || null;
                        const uid = matched && (matched.UserId || matched.userId) || null;
                        if (typeof uid === 'string' && uid.length > 0) return uid;
                    }
                } catch (e) {
                    console.warn('[QueueSelect] resolveUserId from credentials failed', e);
                }
                return null;
            };
            const serverId = resolveServerId();
            const userId = resolveUserId();
            const withPlaybackContext = (payload) => {
                if (!payload || typeof payload !== 'object') return payload;
                const ctx = { ...payload };
                if (serverId) {
                    ctx.serverId = serverId;
                    ctx.ServerId = serverId;
                }
                if (userId) {
                    ctx.userId = userId;
                    ctx.UserId = userId;
                }
                return ctx;
            };

            const protoMethods = (() => {
                try {
                    return Object.getOwnPropertyNames(Object.getPrototypeOf(pm)).filter(Boolean);
                } catch (_) {
                    return [];
                }
            })();
            console.log(
                '[QueueSelect] tapToken=',
                token,
                'itemId=',
                itemId,
                'playlistItemId=',
                playlistItemId,
                'playlistLen=',
                playlist.length,
                'serverId=',
                serverId,
                'userId=',
                userId,
            );
            console.log('[QueueSelect] pm methods:', protoMethods.join(', '));

            // 1) Web queue path only when queue exists.
            if (playlist.length > 0 && typeof pm.setCurrentPlaylistItem === 'function') {
                try {
                    pm.setCurrentPlaylistItem(playlistItemId || targetItemId);
                    console.log('[QueueSelect] used pm.setCurrentPlaylistItem (queue mode)');
                    return true;
                } catch (e) {
                    console.warn('[QueueSelect] pm.setCurrentPlaylistItem failed', e);
                }
            }

            // 2) Some players expose setCurrentPlaylistItem on current player (queue mode).
            const player = readMember(pm, 'getCurrentPlayer');
            if (playlist.length > 0 && player && typeof player.setCurrentPlaylistItem === 'function') {
                try {
                    player.setCurrentPlaylistItem(playlistItemId || targetItemId);
                    console.log('[QueueSelect] used player.setCurrentPlaylistItem (queue mode)');
                    return true;
                } catch (e) {
                    console.warn('[QueueSelect] player.setCurrentPlaylistItem failed', e);
                }
            }

            // 3) Direct playback/command fallbacks (non-queue mode).
            const tryAttempt = async (label, fn) => {
                if (token !== this._queueSelectToken) {
                    console.warn('[QueueSelect] skip stale attempt:', label);
                    return false;
                }
                const before = currentPlaybackItemId();
                try {
                    const result = fn();
                    if (result && typeof result.then === 'function') {
                        await result;
                    }
                    await sleep(650);
                    const after = currentPlaybackItemId();
                    const switchedToTarget = !!after && after === targetItemId;
                    const switchedAway = !!after && !!before && after !== before;
                    const switchedFromUnknown = !before && !!after;
                    if (switchedToTarget || switchedAway || switchedFromUnknown) {
                        console.log('[QueueSelect] attempt ok:', label, 'before=', before, 'after=', after);
                        return true;
                    }

                    // Some Jellyfin runtimes do not expose currentItem/getCurrentItem while player is active
                    // ("player cannot be null"). In that case we cannot verify state transition reliably.
                    // If the play call did not throw and both probes are null, treat as success to avoid
                    // retry storms and fallback re-navigation/restart loops.
                    if (!before && !after && label.startsWith('pm.play#')) {
                        console.warn(
                            '[QueueSelect] attempt assumed-ok (state unavailable):',
                            label,
                            'before=',
                            before,
                            'after=',
                            after,
                        );
                        return true;
                    }

                    console.warn('[QueueSelect] attempt no-switch:', label, 'before=', before, 'after=', after);
                    return false;
                } catch (e) {
                    console.warn('[QueueSelect] attempt failed:', label, e);
                    return false;
                }
            };

            // 4) Generic play fallbacks with safer ids-first payloads.
            const playFn = typeof pm.play === 'function' ? pm.play.bind(pm) : null;
            if (playFn) {
                const attempts = [
                    // Keep only ids-based object payloads for this runtime.
                    () => playFn(withPlaybackContext({ ids: [targetItemId], startIndex: 0, mediaType: 'Video', fullscreen: true })),
                    () => playFn(withPlaybackContext({ ids: [targetItemId], startPositionTicks: 0, mediaType: 'Video', fullscreen: true })),
                    () => playFn(withPlaybackContext({ ids: [targetItemId], startIndex: 0 })),
                    () => playFn(withPlaybackContext({ ids: [targetItemId] })),
                ];
                for (const [idx, attempt] of attempts.entries()) {
                    if (await tryAttempt(`pm.play#${idx + 1}`, attempt)) return true;
                }
            }

            // 5) fallback: details route + auto click Play.
            const navigated = this._navigateToDetails(targetItemId);
            if (navigated) {
                console.warn('[QueueSelect] fallback details+autoplay start');
                const clicked = await this._autoPlayFromDetails(targetItemId, token);
                if (clicked) return true;
            }

            console.warn('[QueueSelect] no working playback method');
            return false;
        } catch (e) {
            console.error('[QueueSelect] error', e);
            return false;
        } finally {
            if (token === this._queueSelectToken) {
                this._cancelQueueSelectAutoplay();
            }
        }
    },

    getQueueData() {
        const send = (payload) => {
            try {
                window.NativeInterface.onQueueDataReceived(JSON.stringify(payload));
            } catch (err) {
                console.error('[QueueData] onQueueDataReceived failed', err);
            }
        };

        const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
        const uuidLike = (s) => typeof s === 'string' && UUID_RE.test(s);

        const firstUuidInString = (value) => {
            if (typeof value !== 'string') return null;
            const match = value.match(UUID_RE);
            return match ? match[0] : null;
        };

        const itemIdFrom = (o) => {
            if (!o || typeof o !== 'object') return null;
            return o.Id || o.id || o.ItemId || o.itemId || o.PrimaryItemId || o.primaryItemId || null;
        };

        const itemIdFromPlayer = (pm, readMember) => {
            const player = readMember(pm, 'getCurrentPlayer');
            if (!player || typeof player !== 'object') return null;

            const direct = itemIdFrom(player)
                || itemIdFrom(readMember(player, 'item'))
                || itemIdFrom(readMember(player, 'currentItem'))
                || itemIdFrom(readMember(player, 'getCurrentItem'));
            if (direct && uuidLike(String(direct))) return direct;

            const candidateValues = [
                readMember(player, 'currentSrc'),
                player.currentSrc,
                readMember(player, 'src'),
                player.src,
                readMember(player, 'url'),
                player.url,
            ];
            for (const c of candidateValues) {
                const found = firstUuidInString(c);
                if (found) return found;
            }

            const candidateObjects = [
                readMember(player, 'currentMediaSource'),
                player.currentMediaSource,
                readMember(player, 'mediaSource'),
                player.mediaSource,
                readMember(player, 'source'),
                player.source,
            ];
            for (const obj of candidateObjects) {
                const fromObj = itemIdFrom(obj);
                if (fromObj && uuidLike(String(fromObj))) return fromObj;
                const packed = (() => {
                    try {
                        return JSON.stringify(obj);
                    } catch (_) {
                        return null;
                    }
                })();
                const fromPacked = firstUuidInString(packed);
                if (fromPacked) return fromPacked;
            }
            return null;
        };

        const itemIdFromLocation = () => {
            try {
                const href = String(window.location && window.location.href || '');
                const hash = String(window.location && window.location.hash || '');
                const search = String(window.location && window.location.search || '');
                const candidates = [href, hash, search];

                // Common query params used by Jellyfin routes.
                for (const raw of candidates) {
                    const m = /(?:[?&#](?:id|itemId)=)([0-9a-f-]{36})/i.exec(raw);
                    if (m && uuidLike(m[1])) return m[1];
                }
                // Media URL patterns like /Videos/{id}/...
                for (const raw of candidates) {
                    const m = /\/Videos\/([0-9a-f-]{36})(?:\/|$)/i.exec(raw);
                    if (m && uuidLike(m[1])) return m[1];
                }
            } catch (_) { /* ignore */ }
            return null;
        };

        const deepFindUuid = (root, maxDepth = 4) => {
            const seen = new Set();
            let visited = 0;
            const maxVisited = 400;
            const walk = (node, depth) => {
                if (visited > maxVisited || depth > maxDepth || node == null) return null;
                visited++;
                if (typeof node === 'string') return firstUuidInString(node);
                if (typeof node !== 'object') return null;
                if (seen.has(node)) return null;
                seen.add(node);

                const keys = Object.keys(node);
                // Prefer id-like keys first.
                keys.sort((a, b) => {
                    const ai = /id|item|video|media|play/i.test(a) ? 0 : 1;
                    const bi = /id|item|video|media|play/i.test(b) ? 0 : 1;
                    return ai - bi;
                });
                for (const k of keys) {
                    const child = node[k];
                    if (typeof child === 'string' && uuidLike(child)) return child;
                    const found = walk(child, depth + 1);
                    if (found) return found;
                }
                return null;
            };
            return walk(root, 0);
        };

        /** Prefer a non-empty playlist source (web may expose queue on getPlaylist but not getCurrentPlaylistItems). */
        const bestPlaylistArray = (pm, readMember) => {
            const out = [];
            for (const n of ['getPlaylist', 'getCurrentPlaylistItems', 'playlist']) {
                let v = readMember(pm, n);
                if (!v) continue;
                if (!Array.isArray(v)) {
                    try {
                        v = Array.from(v);
                    } catch (_) {
                        continue;
                    }
                }
                if (v && v.length) out.push(v);
            }
            out.sort((a, b) => b.length - a.length);
            return out[0] || null;
        };

        /** Jellyfin sometimes returns "playlistItem0" from getCurrentPlaylistItemId — map to real media Id. */
        const resolvePlaylistItemPlaceholder = (placeholder, pm, readMember) => {
            if (placeholder == null || uuidLike(placeholder)) return placeholder;
            const m = /^playlistItem(\d+)$/i.exec(String(placeholder));
            if (!m) return null;
            const idx = parseInt(m[1], 10);
            const arr = bestPlaylistArray(pm, readMember);
            if (!arr || !arr[idx]) return null;
            const row = arr[idx];
            return row.Id || row.ItemId || row.id || null;
        };

        const parentIdFrom = (o) => {
            if (!o || typeof o !== 'object') return null;
            return o.ParentId || o.parentId || o.SeasonId || o.seasonId || o.SeriesId || o.seriesId
                || o.AlbumId || o.albumId || o.PlaylistId || o.playlistId || null;
        };

        const unwrapMaybePromise = (value, onResolved) => {
            if (value && typeof value.then === 'function') {
                value.then(onResolved).catch((err) => {
                    console.warn('[QueueData] async currentItem failed:', err && err.message);
                    onResolved(null);
                });
                return true;
            }
            onResolved(value);
            return false;
        };

        try {
            const pm = (window.NavigationHelper && window.NavigationHelper.playbackManager)
                || (window.Emby && window.Emby.PlaybackManager)
                || null;

            console.log('[QueueData] playbackManager found:', !!pm);

            if (!pm) {
                console.log('[QueueData] No playbackManager, keys on NavigationHelper:', window.NavigationHelper ? Object.keys(window.NavigationHelper) : 'N/A');
                console.log('[QueueData] keys on window.Emby:', window.Emby ? Object.keys(window.Emby) : 'N/A');
                send({ items: [], currentIndex: 0 });
                return;
            }

            console.log('[QueueData] pm methods:', Object.getOwnPropertyNames(Object.getPrototypeOf(pm)).filter(m => m.toLowerCase().includes('playlist') || m.toLowerCase().includes('queue') || m.toLowerCase().includes('current')).join(', '));

            const readMember = (obj, name) => {
                if (!obj || !name) return null;
                const v = obj[name];
                if (typeof v === 'function') {
                    try {
                        return v.call(obj);
                    } catch (e) {
                        console.warn('[QueueData] call', name, e);
                        return null;
                    }
                }
                return v != null ? v : null;
            };

            let playlist = readMember(pm, 'getCurrentPlaylistItems')
                || readMember(pm, 'getPlaylist')
                || readMember(pm, 'playlist');
            if (!Array.isArray(playlist) && playlist) {
                try {
                    playlist = Array.from(playlist);
                } catch (_) {
                    playlist = null;
                }
            }
            if (!Array.isArray(playlist)) {
                playlist = null;
            }

            const readCurrentItemObject = (cb) => {
                // Always use readMember for methods: Jellyfin's currentItem() throws if player is null.
                let value = readMember(pm, 'currentItem')
                    || readMember(pm, 'getCurrentItem');

                if (value == null) {
                    const player = readMember(pm, 'getCurrentPlayer');
                    if (player) {
                        value = readMember(player, 'currentItem')
                            || readMember(player, 'getCurrentItem')
                            || readMember(player, 'item');
                    }
                }

                if (value != null) {
                    return unwrapMaybePromise(value, cb);
                }
                cb(null);
                return false;
            };

            let playlistItemId = readMember(pm, 'getCurrentPlaylistItemId');
            if (playlistItemId && typeof playlistItemId === 'object') {
                playlistItemId = itemIdFrom(playlistItemId) || playlistItemId.Id || playlistItemId.id || null;
            }
            const resolvedPlaylistId = resolvePlaylistItemPlaceholder(playlistItemId, pm, readMember);
            if (resolvedPlaylistId && resolvedPlaylistId !== playlistItemId) {
                console.log('[QueueData] Resolved playlist placeholder', playlistItemId, '->', resolvedPlaylistId);
                playlistItemId = resolvedPlaylistId;
            } else if (playlistItemId && !uuidLike(String(playlistItemId))) {
                console.warn('[QueueData] Omitting non-UUID playlist id for native API:', playlistItemId);
                playlistItemId = null;
            }

            const finish = (currentItemObj) => {
                const idFromItem = itemIdFrom(currentItemObj);
                const playerItemId = itemIdFromPlayer(pm, readMember);
                const locationItemId = itemIdFromLocation();
                const deepItemId = deepFindUuid(
                    currentItemObj
                    || readMember(pm, 'getCurrentPlayer')
                    || readMember(pm, 'currentItem')
                    || readMember(pm, 'getCurrentItem')
                    || pm
                    || window.NavigationHelper
                    || (window.Emby && window.Emby.Page)
                    || null,
                    4,
                );
                const currentItemId = idFromItem || playlistItemId || playerItemId || locationItemId || deepItemId || null;

                console.log('[QueueData] playlist length:', playlist ? playlist.length : 'null', 'currentItemId:', currentItemId);

                if (!playlist || playlist.length === 0) {
                    const fallbackItemId = idFromItem || playlistItemId || playerItemId || locationItemId || deepItemId || null;
                    const parentRaw = parentIdFrom(currentItemObj);
                    const parentId = (parentRaw && uuidLike(String(parentRaw))) ? parentRaw : null;
                    console.log(
                        '[QueueData] Empty playlist fallback itemId:',
                        fallbackItemId,
                        'parentId:',
                        parentId,
                        'playerItemId:',
                        playerItemId,
                        'locationItemId:',
                        locationItemId,
                        'deepItemId:',
                        deepItemId,
                    );
                    if (currentItemObj && typeof currentItemObj === 'object') {
                        try {
                            console.log('[QueueData] currentItem keys:', Object.keys(currentItemObj).join(', '));
                        } catch (_) { /* ignore */ }
                    }
                    const emptyPayload = { items: [], currentIndex: 0 };
                    if (fallbackItemId && uuidLike(String(fallbackItemId))) {
                        emptyPayload.currentItemId = fallbackItemId;
                    }
                    if (parentId) {
                        emptyPayload.parentId = parentId;
                    }
                    // Always include debug candidates so Android logs can show why fallback failed.
                    emptyPayload.debug = {
                        idFromItem: idFromItem || null,
                        playlistItemId: playlistItemId || null,
                        playerItemId: playerItemId || null,
                        locationItemId: locationItemId || null,
                        deepItemId: deepItemId || null,
                        href: String(window.location && window.location.href || ''),
                    };
                    if (!emptyPayload.currentItemId && !emptyPayload.parentId) {
                        const player = readMember(pm, 'getCurrentPlayer');
                        try {
                            console.warn('[QueueData] player keys:', player && typeof player === 'object' ? Object.keys(player).join(', ') : 'none');
                        } catch (_) { /* ignore */ }
                        console.warn('[QueueData] Could not derive UUID fallback ids; sending empty payload only');
                    }
                    send(emptyPayload);
                    return;
                }

                console.log('[QueueData] First item keys:', Object.keys(playlist[0]).join(', '));

                let currentIndex = 0;
                const items = playlist.map((item, index) => {
                    const rowId = item.Id || item.id || item.ItemId || item.itemId;
                    if (currentItemId && (rowId === currentItemId || item.PlaylistItemId === currentItemId)) {
                        currentIndex = index;
                    }
                    return {
                        itemId: rowId || '',
                        title: item.Name || item.name || '',
                        seriesName: item.SeriesName || item.seriesName || null,
                        runTimeTicks: item.RunTimeTicks || item.runTimeTicks || 0,
                        imageTag: (item.ImageTags && item.ImageTags.Primary) || (item.imageTags && item.imageTags.Primary) || null,
                    };
                });

                console.log('[QueueData] Sending', items.length, 'items, currentIndex:', currentIndex);
                send({ items: items, currentIndex: currentIndex });
            };

            readCurrentItemObject(finish);
        } catch (e) {
            console.error('[QueueData] Error:', e.message, e.stack);
            try {
                window.NativeInterface.onQueueDataReceived(JSON.stringify({ items: [], currentIndex: 0 }));
            } catch (_) { /* ignore */ }
        }
    },

    async execCast(action, args, callback) {
        this.castCallbacks = this.castCallbacks || {};
        this.castCallbacks[action] = callback;
        window.NativeInterface.execCast(action, JSON.stringify(args));
    },

    async castCallback(action, keep, err, result) {
        const callbacks = this.castCallbacks || {};
        const callback = callbacks[action];
        callback && callback(err || null, result);
        if (!keep) {
            delete callbacks[action];
        }
    }
};

function getDeviceProfile(profileBuilder, item) {
    const profile = profileBuilder({
        enableMkvProgressive: false
    });

    profile.CodecProfiles = profile.CodecProfiles.filter(function (i) {
        return i.Type === "Audio";
    });

    profile.CodecProfiles.push({
        Type: "Video",
        Container: "avi",
        Conditions: [
            {
                Condition: "NotEquals",
                Property: "VideoCodecTag",
                Value: "xvid"
            }
        ]
    });

    profile.CodecProfiles.push({
        Type: "Video",
        Codec: "h264",
        Conditions: [
            {
                Condition: "EqualsAny",
                Property: "VideoProfile",
                Value: "high|main|baseline|constrained baseline"
            },
            {
                Condition: "LessThanEqual",
                Property: "VideoLevel",
                Value: "41"
            }]
    });

    profile.TranscodingProfiles.reduce(function (profiles, p) {
        if (p.Type === "Video" && p.CopyTimestamps === true && p.VideoCodec === "h264") {
            p.AudioCodec += ",ac3";
            profiles.push(p);
        }
        return profiles;
    }, []);

    return profile;
}

window.NativeShell.AppHost = {
    init() {},
    getDefaultLayout() {
        return "mobile";
    },
    supports(command) {
        return features.includes(command.toLowerCase());
    },
    getDeviceProfile,
    getSyncProfile: getDeviceProfile,
    deviceName() {
        return deviceName;
    },
    deviceId() {
        return deviceId;
    },
    appName() {
        return appName;
    },
    appVersion() {
        return appVersion;
    },
    exit() {
        window.NativeInterface.exitApp();
    }
};
