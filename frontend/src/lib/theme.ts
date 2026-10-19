/** Key under which an explicit light or dark choice is remembered. */
export const THEME_STORAGE_KEY = "saarthi-theme";

/**
 * Runs before the page paints (inlined in the document head) so a saved choice never flashes the
 * other theme. Storage can be unavailable (private windows); the system setting is used then.
 */
export const THEME_SCRIPT = `(function(){try{var t=localStorage.getItem("${THEME_STORAGE_KEY}");if(t==="light"||t==="dark"){document.documentElement.dataset.theme=t;}}catch(e){}})();`;
