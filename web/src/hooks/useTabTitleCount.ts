import { useEffect } from "react";
import { LIVE_DOWN_GRACE_MS, type LiveStatus } from "../utils/liveClient";
import { setTitleCount } from "../utils/titleCount";

/**
 * Feeds the bell's unseen total to the tab title (1.2 D8), but only while the number can be kept current: a `live` socket, or a `quiet` one (a hidden
 * tab whose personal frames still arrive). A socket that goes `down` keeps the number for the same 5 s grace as the "Reconnecting..." badge
 * (`LIVE_DOWN_GRACE_MS`: a token expiry's re-mint or a server restart must not blink the title) and loses it after that; connecting, paused, idle
 * (the 8-hour close) and stopped carry none at once, so the title never shows a number it cannot keep current (1.2 A17). Unmounting (sign-out) clears it.
 */
export function useTabTitleCount(unseenTotal: number, status: LiveStatus): void {
  const current = status === "live" || status === "quiet";
  useEffect(() => {
    if (current) setTitleCount(unseenTotal);
  }, [current, unseenTotal]);
  useEffect(() => {
    if (current) return;
    if (status !== "down") {
      setTitleCount(0);
      return;
    }
    const timer = setTimeout(() => setTitleCount(0), LIVE_DOWN_GRACE_MS);
    return () => clearTimeout(timer);
  }, [current, status]);
  useEffect(() => () => setTitleCount(0), []);
}
