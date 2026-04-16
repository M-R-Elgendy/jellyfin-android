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

    getQueueData() {
        try {
            const pm = (window.NavigationHelper && window.NavigationHelper.playbackManager)
                || (window.Emby && window.Emby.PlaybackManager)
                || null;

            console.log('[QueueData] playbackManager found:', !!pm);

            if (!pm) {
                console.log('[QueueData] No playbackManager, keys on NavigationHelper:', window.NavigationHelper ? Object.keys(window.NavigationHelper) : 'N/A');
                console.log('[QueueData] keys on window.Emby:', window.Emby ? Object.keys(window.Emby) : 'N/A');
                window.NativeInterface.onQueueDataReceived(JSON.stringify({ items: [], currentIndex: 0 }));
                return;
            }

            console.log('[QueueData] pm methods:', Object.getOwnPropertyNames(Object.getPrototypeOf(pm)).filter(m => m.toLowerCase().includes('playlist') || m.toLowerCase().includes('queue') || m.toLowerCase().includes('current')).join(', '));

            let playlist = null;
            if (typeof pm.getCurrentPlaylistItems === 'function') {
                playlist = pm.getCurrentPlaylistItems();
            } else if (typeof pm.getPlaylist === 'function') {
                playlist = pm.getPlaylist();
            } else if (typeof pm.playlist === 'function') {
                playlist = pm.playlist();
            }

            let currentItemId = null;
            if (typeof pm.getCurrentPlaylistItemId === 'function') {
                currentItemId = pm.getCurrentPlaylistItemId();
            } else if (typeof pm.currentItem === 'function') {
                const ci = pm.currentItem();
                currentItemId = ci && ci.Id;
            } else if (typeof pm.getCurrentItem === 'function') {
                const ci = pm.getCurrentItem();
                currentItemId = ci && ci.Id;
            }

            console.log('[QueueData] playlist length:', playlist ? playlist.length : 'null', 'currentItemId:', currentItemId);

            if (!playlist || playlist.length === 0) {
                console.log('[QueueData] Empty playlist, trying to get current item info for fallback');
                let currentItemObj = null;
                if (typeof pm.currentItem === 'function') {
                    currentItemObj = pm.currentItem();
                } else if (typeof pm.getCurrentItem === 'function') {
                    currentItemObj = pm.getCurrentItem();
                }
                const fallbackItemId = (currentItemObj && currentItemObj.Id) || currentItemId || null;
                const parentId = (currentItemObj && (currentItemObj.ParentId || currentItemObj.parentId)) || null;
                console.log('[QueueData] Fallback currentItemId:', fallbackItemId, 'parentId:', parentId);
                if (currentItemObj) {
                    console.log('[QueueData] currentItem keys:', Object.keys(currentItemObj).join(', '));
                }
                window.NativeInterface.onQueueDataReceived(JSON.stringify({
                    items: [],
                    currentIndex: 0,
                    currentItemId: fallbackItemId,
                    parentId: parentId
                }));
                return;
            }

            console.log('[QueueData] First item keys:', Object.keys(playlist[0]).join(', '));

            let currentIndex = 0;
            const items = playlist.map((item, index) => {
                if (currentItemId && (item.Id === currentItemId || item.PlaylistItemId === currentItemId)) {
                    currentIndex = index;
                }
                return {
                    itemId: item.Id || item.ItemId || '',
                    title: item.Name || item.name || '',
                    seriesName: item.SeriesName || item.seriesName || null,
                    runTimeTicks: item.RunTimeTicks || item.runTimeTicks || 0,
                    imageTag: (item.ImageTags && item.ImageTags.Primary) || (item.imageTags && item.imageTags.Primary) || null
                };
            });

            console.log('[QueueData] Sending', items.length, 'items, currentIndex:', currentIndex);
            window.NativeInterface.onQueueDataReceived(JSON.stringify({ items: items, currentIndex: currentIndex }));
        } catch (e) {
            console.error('[QueueData] Error:', e.message, e.stack);
            window.NativeInterface.onQueueDataReceived(JSON.stringify({ items: [], currentIndex: 0 }));
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
