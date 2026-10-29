/*
 * Copyright 2026 Bad Monkey, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.badmonkey.agentspaces.groovy

import ai.badmonkey.agentspaces.agent.AgentSpaces
import ai.badmonkey.agentspaces.agent.content.Manifest
import ai.badmonkey.agentspaces.api.space.Lease
import ai.badmonkey.agentspaces.content.Content
import ai.badmonkey.agentspaces.content.Fetch
import ai.badmonkey.agentspaces.content.FetchOptions
import ai.badmonkey.agentspaces.content.PublishOptions
import groovy.transform.CompileStatic

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Path
import java.time.Duration

/**
 * The content swarm in Groovy (srad/large-networks-bulk-data.md §7.2, SPEC v0.1.23 §8):
 * {@code group.content} is the group's {@link Content} when {@code content { enabled }} is on,
 * {@code publish} takes bytes, a {@link File}, a {@link Path} or a path string with named
 * options, {@code fetch} takes a timeout and a closure over the completed fetch, and a
 * {@link Fetch} reads itself as bytes.
 */
@CompileStatic
final class ContentExtensions {

    private ContentExtensions() {
    }

    /** The group's content swarm; {@code content { enabled }} in the fleet DSL turns it on. */
    static Content getContent(AgentSpaces.GroupContext self) {
        self.provider(Content.TYPE)
                .map { (Content) it }
                .orElseThrow { new IllegalStateException(
                        "the content swarm is not enabled in this group (agentspaces.content.enabled, or content { enabled } in the fleet DSL)") }
    }

    /**
     * Publishes an artifact: {@code name}, {@code mediaType}, {@code pieceSize}, {@code lease}
     * (default 7d), {@code attrs}, {@code inPlace} (a file served from where it is), {@code replicate}.
     */
    static Manifest publish(Content self, Map<String, ?> options, Object source) {
        PublishOptions opts = self.publishOptions()
        if (options.containsKey('name')) {
            opts = opts.withName(options.name.toString())
        }
        if (options.containsKey('mediaType')) {
            opts = opts.withMediaType(options.mediaType.toString())
        }
        if (options.containsKey('pieceSize')) {
            opts = opts.withPieceSize(pieceSize(options.pieceSize))
        }
        if (options.containsKey('lease')) {
            opts = opts.withLease(Lease.of(Coercions.duration(options.lease)))
        }
        if (options.containsKey('attrs')) {
            opts = opts.withAttrs(((Map<?, ?>) options.attrs).collectEntries { k, v -> [(k.toString()): v.toString()] } as Map<String, String>)
        }
        if (options.containsKey('inPlace')) {
            opts = opts.withInPlace(Boolean.parseBoolean(options.inPlace.toString()))
        }
        if (options.containsKey('replicate')) {
            opts = opts.withReplicate(Boolean.parseBoolean(options.replicate.toString()))
        }
        switch (source) {
            case byte[]: return self.publish((byte[]) source, opts)
            case Path: return self.publish((Path) source, opts)
            case File: return self.publish(((File) source).toPath(), opts)
            case CharSequence: return self.publish(Path.of(source.toString()), opts)
            default: throw new IllegalArgumentException("publish takes bytes, a File, a Path, or a path string, not ${source?.getClass()?.name}")
        }
    }

    /** {@code content.publish(bytes)} / {@code content.publish(file)} with the defaults. */
    static Manifest publish(Content self, Object source) {
        publish(self, [:], source)
    }

    /**
     * Fetches a root and waits up to {@code timeout} (default 10m) for it: the closure gets the
     * completed {@link Fetch} and its result is returned; a fetch that is not complete in time
     * throws with the fetch's trace.
     */
    static <T> T fetch(Content self, Map<String, ?> options, String root, Closure<T> body) {
        Fetch fetch = self.fetch(root, FetchOptions.defaults())
        Duration timeout = options.containsKey('timeout') ? Coercions.duration(options.timeout) : Duration.ofMinutes(10)
        if (!fetch.await(timeout)) {
            throw new IllegalStateException("fetch of ${root} did not complete within ${timeout}: ${fetch.trace().join('; ')}")
        }
        body.call(fetch)
    }

    static <T> T fetch(Content self, String root, Closure<T> body) {
        fetch(self, [:], root, body)
    }

    /** {@code fetch.await('2m')}: whether the fetch completed within the duration. */
    static boolean await(Fetch self, Object timeout) {
        self.await(Coercions.duration(timeout))
    }

    /** The whole artifact as bytes; the fetch must be complete. */
    static byte[] getBytes(Fetch self) {
        SeekableByteChannel channel = self.open()
        try {
            ByteBuffer buffer = ByteBuffer.allocate((int) channel.size())
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // drain
            }
            return buffer.array()
        } finally {
            channel.close()
        }
    }

    /** A piece size as a number of bytes or a string with a {@code k} or {@code m} suffix ({@code '256k'}). */
    private static int pieceSize(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue()
        }
        String text = value.toString().trim().toLowerCase(Locale.ROOT)
        if (text.endsWith('m')) {
            return Integer.parseInt(text[0..-2].trim()) * 1024 * 1024
        }
        if (text.endsWith('k')) {
            return Integer.parseInt(text[0..-2].trim()) * 1024
        }
        Integer.parseInt(text)
    }

    /** The artifact as text (UTF-8); the fetch must be complete. */
    static String getText(Fetch self) {
        new String(getBytes(self), 'UTF-8')
    }
}
