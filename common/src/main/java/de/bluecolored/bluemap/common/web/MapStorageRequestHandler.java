/*
 * This file is part of BlueMap, licensed under the MIT License (MIT).
 *
 * Copyright (c) Blue (Lukas Rieger) <https://bluecolored.de>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.bluecolored.bluemap.common.web;

import de.bluecolored.bluemap.api.ContentTypeRegistry;
import de.bluecolored.bluemap.common.web.http.HttpRequest;
import de.bluecolored.bluemap.common.web.http.HttpRequestHandler;
import de.bluecolored.bluemap.common.web.http.HttpResponse;
import de.bluecolored.bluemap.common.web.http.HttpStatusCode;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.storage.GridStorage;
import de.bluecolored.bluemap.core.storage.MapStorage;
import de.bluecolored.bluemap.core.storage.compression.CompressedInputStream;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Getter @Setter
public class MapStorageRequestHandler implements HttpRequestHandler {

    private static final Pattern TILE_PATTERN = Pattern.compile("tiles/([\\d/]+)/x(-?[\\d/]+)z(-?[\\d/]+).*");

    // Upstream serves tiles with a 1-day max-age; on a live, frequently-updated map behind a CDN
    // that is long enough for a re-rendered tile to stay stale for a full day. Keep it short.
    private static final long TILE_MAX_AGE_SECONDS = TimeUnit.MINUTES.toSeconds(1);

    // Everything under `live/` is rewritten every few seconds (player positions, marker feeds) and,
    // like tiles, carries no ETag/Last-Modified - so a cache cannot revalidate it, it just serves
    // its copy until it expires. Upstream's 1-day max-age therefore lets a CDN pin one snapshot for
    // a day: in prod that surfaced as "nobody is ever online" (an empty players.json edge-cached at
    // a quiet moment) and as live markers frozen mid-update. Short enough that no cache can hold a
    // stale snapshot, long enough to still collapse the web-app's 1s player poll at the edge.
    private static final long LIVE_MAX_AGE_SECONDS = 5;

    // Map metadata (settings.json, textures.json, assets) only changes on a re-render or a config
    // change, so it keeps upstream's long max-age.
    private static final long META_MAX_AGE_SECONDS = TimeUnit.DAYS.toSeconds(1);

    // Player heads are written per player rather than per render - a player whose skin changes, or
    // who is seen for the first time, gets a new one at any moment. A day of cache would mean a
    // changed skin does not show until tomorrow.
    private static final long PLAYERHEAD_MAX_AGE_SECONDS = TimeUnit.MINUTES.toSeconds(10);

    private static final String LIVE_PATH_PREFIX = "live/";
    private static final String PLAYERHEADS_PATH_PREFIX = "assets/playerheads/";

    private @NonNull MapStorage mapStorage;

    @SuppressWarnings("resource")
    @Override
    public HttpResponse handle(HttpRequest request) {
        String path = request.getPath();

        //normalize path
        if (path.startsWith("/")) path = path.substring(1);
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);

        try {

            // provide map-tiles
            Matcher tileMatcher = TILE_PATTERN.matcher(path);
            if (tileMatcher.matches()) {
                int lod = Integer.parseInt(tileMatcher.group(1));
                int x = Integer.parseInt(tileMatcher.group(2).replace("/", ""));
                int z = Integer.parseInt(tileMatcher.group(3).replace("/", ""));

                GridStorage gridStorage = lod == 0 ? mapStorage.hiresTiles() : mapStorage.lowresTiles(lod);
                CompressedInputStream in = gridStorage.read(x, z);
                if (in == null) return new HttpResponse(HttpStatusCode.NO_CONTENT);

                HttpResponse response = new HttpResponse(HttpStatusCode.OK);
                // Map tiles are re-written in place whenever the world changes, but the response
                // carries no ETag/Last-Modified, so a cached copy cannot be revalidated - it is just
                // served until it expires. Upstream's 1-day max-age means a tile that changed (or,
                // as we hit in prod, a void tile that got re-rendered with real terrain) stays stale
                // for up to a day. Behind a CDN that edge-caches by this header that became visible,
                // never-healing holes. Keep it short so any cache refreshes quickly; the webapp still
                // force-revalidates on its own update pass on top of this.
                cacheFor(response, TILE_MAX_AGE_SECONDS);

                if (lod == 0) response.addHeader("Content-Type", "application/octet-stream");
                else response.addHeader("Content-Type", "image/png");

                writeToResponse(in, response, request);
                return response;
            }

            // provide meta-data
            CompressedInputStream in = switch (path) {
                case "settings.json" -> mapStorage.settings().read();
                case "textures.json" -> mapStorage.textures().read();
                case "live/markers.json" -> mapStorage.markers().read();
                case "live/players.json" -> mapStorage.players().read();
                default -> path.startsWith("assets/") ? mapStorage.asset(path.substring(7)).read() : null;
            };
            if (in != null){
                HttpResponse response = new HttpResponse(HttpStatusCode.OK);
                cacheFor(response, maxAgeFor(path));
                response.addHeader("Content-Type", ContentTypeRegistry.fromFileName(path));
                writeToResponse(in, response, request);
                return response;
            }

        } catch (NumberFormatException | NoSuchElementException ignore){
        } catch (IOException ex) {
            Logger.global.logError("Failed to read map-tile for web-request.", ex);
            return new HttpResponse(HttpStatusCode.INTERNAL_SERVER_ERROR);
        }

        // Not found is a *timing* answer here as often as a permanent one: a player head is written
        // the first time that player is seen, so the web-app can ask for one moments before it
        // exists. A CDN that caches the 404 (Cloudflare does, by default, even without a header)
        // would keep answering "no such head" long after we wrote it.
        HttpResponse notFound = new HttpResponse(HttpStatusCode.NOT_FOUND);
        notFound.addHeader("Cache-Control", "no-store");
        return notFound;
    }

    /**
     * How long a cache may keep a piece of map metadata, by what it is: player heads change per
     * player, everything else only on a re-render or a config change.
     */
    private long maxAgeFor(String path) {
        if (path.startsWith(LIVE_PATH_PREFIX)) return LIVE_MAX_AGE_SECONDS;
        if (path.startsWith(PLAYERHEADS_PATH_PREFIX)) return PLAYERHEAD_MAX_AGE_SECONDS;
        return META_MAX_AGE_SECONDS;
    }

    /**
     * Sets the whole Cache-Control header in one call. {@code addHeader} puts by name rather than
     * appending, so the "public" and "max-age=..." directives have to go out together - added
     * separately the second call silently replaces the first.
     */
    private void cacheFor(HttpResponse response, long maxAgeSeconds) {
        response.addHeader("Cache-Control", "public", "max-age=" + maxAgeSeconds);
    }

    private void writeToResponse(CompressedInputStream data, HttpResponse response, HttpRequest request) throws IOException {
        Compression compression = data.getCompression();
        if (
                compression != Compression.NONE &&
                request.hasHeaderValue("Accept-Encoding", compression.getId())
        ) {
            response.addHeader("Content-Encoding", compression.getId());
            response.setBody(data);
        } else if (
                compression != Compression.GZIP &&
                !response.hasHeaderValue("Content-Type", "image/png") &&
                request.hasHeaderValue("Accept-Encoding", Compression.GZIP.getId())
        ) {
            response.addHeader("Content-Encoding", Compression.GZIP.getId());
            ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            try (data; OutputStream os = Compression.GZIP.compress(byteOut)) {
                data.decompress().transferTo(os);
            }
            byte[] compressedData = byteOut.toByteArray();
            response.setBody(new ByteArrayInputStream(compressedData));
        } else {
            response.setBody(data.decompress());
        }
    }

}
