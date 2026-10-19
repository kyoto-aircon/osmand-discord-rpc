(function (exports, vendetta) {
    "use strict";

    // Kettu plugin bundle in the same shape Kettu's loader expects:
    // an IIFE that receives the `vendetta` global as its second argument and
    // exposes `exports.default = { onLoad, onUnload }` as its completion value.

    const { logger } = vendetta;
    const { FluxDispatcher } = vendetta.metro.common;

    const ENDPOINT = "http://127.0.0.1:8090/status";
    const POLL_MS = 2000;
    const SOCKET_ID = "OsmAndTripRPC";

    let timer = null;
    let inFlight = false;
    let unloaded = false;
    let activityShown = false;
    let tripStart = 0;
    let lastState = null;

    function dispatchActivity(activity) {
        FluxDispatcher.dispatch({
            type: "LOCAL_ACTIVITY_UPDATE",
            activity: activity,
            socketId: SOCKET_ID,
        });
    }

    function clearPresence() {
        if (!activityShown) return;
        activityShown = false;
        lastState = null;
        try {
            dispatchActivity(null);
            logger.info("[OsmAndTripRPC] presence cleared");
        } catch (e) {
            logger.error("[OsmAndTripRPC] failed to clear presence", e);
        }
    }

    function publish(status) {
        if (unloaded) return;
        if (!status || status.recording !== true) {
            clearPresence();
            return;
        }
        const state = String(status.state || "");
        if (!state) {
            clearPresence();
            return;
        }
        if (activityShown && lastState === state) return;

        if (!activityShown) {
            tripStart = Date.now() - (Number(status.elapsedSeconds) || 0) * 1000;
            activityShown = true;
        }
        lastState = state;

        try {
            dispatchActivity({
                application_id: "0",
                // Discord shows `name` on the compact presence row, so put the trip line there:
                // details/state are only visible after opening the profile.
                name: state,
                type: 0,
                flags: 1,
                details: "OsmAnd trip recording",
                state: state,
                timestamps: { start: tripStart },
            });
            logger.info("[OsmAndTripRPC] presence " + state);
        } catch (e) {
            activityShown = false;
            logger.error("[OsmAndTripRPC] failed to set presence", e);
        }
    }

    async function poll() {
        if (inFlight || unloaded) return;
        inFlight = true;
        try {
            const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
            const timeout = controller ? setTimeout(() => controller.abort(), 1500) : null;
            const response = await fetch(ENDPOINT, controller ? { signal: controller.signal } : undefined);
            if (timeout) clearTimeout(timeout);
            if (unloaded) return;
            if (!response.ok) {
                clearPresence();
                return;
            }
            publish(await response.json());
        } catch (e) {
            if (!unloaded) {
                logger.warn("[OsmAndTripRPC] status fetch failed: " + (e && e.message ? e.message : String(e)));
                clearPresence();
            }
        } finally {
            inFlight = false;
        }
    }

    var plugin = {
        onLoad() {
            unloaded = false;
            logger.info("[OsmAndTripRPC] loaded, polling " + ENDPOINT);
            poll();
            timer = setInterval(poll, POLL_MS);
        },
        onUnload() {
            unloaded = true;
            if (timer) clearInterval(timer);
            timer = null;
            clearPresence();
            logger.info("[OsmAndTripRPC] unloaded");
        },
    };

    exports.default = plugin;
    Object.defineProperty(exports, "__esModule", { value: true });
    return exports;
})({}, vendetta);
