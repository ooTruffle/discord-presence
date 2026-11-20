package com.ootruffle.discordpresence.ipc;

/**
 * One snapshot of what Discord should show. A record so {@link PresenceClient} can skip
 * sending an update that is equal to the one already shown.
 *
 * @param start     epoch millis for the elapsed timer, or 0 for none
 * @param partySize current players, shown with {@code partyMax} when both are positive
 */
public record Presence(String details, String state, long start,
                       String largeImage, String largeText,
                       String smallImage, String smallText,
                       int partySize, int partyMax) {
}
