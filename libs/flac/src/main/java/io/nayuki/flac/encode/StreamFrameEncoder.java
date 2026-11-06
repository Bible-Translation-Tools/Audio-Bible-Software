/*
 * FLAC library (Java)
 *
 * Copyright (c) Project Nayuki
 * https://www.nayuki.io/page/flac-library-java
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program (see COPYING.txt and COPYING.LESSER.txt).
 * If not, see <http://www.gnu.org/licenses/>.
 */

package io.nayuki.flac.encode;

import java.io.IOException;
import java.util.Objects;


/**
 * [btt] Encodes one frame at a time, so a caller can stream audio through the encoder.
 * <p>{@link FlacEncoder} takes every sample of the stream at once, which for a long recording is
 * more memory than an Android app has. This does what its loop does for a single block, through
 * the package-private {@link FrameEncoder}, and writes the same bytes.</p>
 */
public final class StreamFrameEncoder {

	private StreamFrameEncoder() {}


	/**
	 * Encodes one block of audio as a FLAC frame.
	 * @param sampleOffset the index of the block's first sample in the stream
	 * @param samples the block, one array per channel, each the same length (1 to 65535)
	 * @param sampleDepth bits per sample
	 * @param sampleRate samples per second
	 * @param opt how hard to search for the smallest encoding
	 * @param out where the frame is written
	 * @return the frame's size in bytes
	 * @throws IOException if writing fails
	 */
	public static int encode(int sampleOffset, long[][] samples, int sampleDepth, int sampleRate,
			SubframeEncoder.SearchOptions opt, BitOutputStream out) throws IOException {
		Objects.requireNonNull(samples);
		Objects.requireNonNull(opt);
		Objects.requireNonNull(out);
		FrameEncoder enc = FrameEncoder.computeBest(sampleOffset, samples, sampleDepth, sampleRate, opt).encoder;
		long startByte = out.getByteCount();
		enc.encode(samples, out);
		long frameSize = out.getByteCount() - startByte;
		if (frameSize < 0 || (int)frameSize != frameSize)
			throw new AssertionError();
		return (int)frameSize;
	}

}
