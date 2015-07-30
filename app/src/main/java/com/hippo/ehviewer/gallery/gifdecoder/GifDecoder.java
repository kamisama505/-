package com.hippo.ehviewer.gallery.gifdecoder;

/**
 * Copyright (c) 2013 Xcellent Creations, Inc.
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

import android.graphics.Bitmap;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.hippo.unifile.UniRandomReadFile;
import com.hippo.yorozuya.IOUtils;

import java.io.IOException;
import java.util.Arrays;

/**
 * Reads frame data from a GIF image source and decodes it into individual frames for animation
 * purposes.  Image data can be read from either and InputStream source or a byte[].
 * <p/>
 * This class is optimized for running animations with the frames, there are no methods to get
 * individual frame images, only to decode the next frame in the animation sequence.  Instead, it
 * lowers its memory footprint by only housing the minimum data necessary to decode the next frame
 * in the animation sequence.
 * <p/>
 * The animation must be manually moved forward using {@link #advance()} before requesting the next
 * frame.  This method must also be called before you request the first frame or an error will
 * occur.
 * <p/>
 * Implementation adapted from sample code published in Lyons. (2004). <em>Java for
 * Programmers</em>, republished under the MIT Open Source License
 */
public class GifDecoder {
    private static final String TAG = GifDecoder.class.getSimpleName();

    /**
     * File read status: No errors.
     */
    public static final int STATUS_OK = 0;
    /**
     * File read status: Error decoding file (may be partially decoded).
     */
    public static final int STATUS_FORMAT_ERROR = 1;
    /**
     * File read status: Unable to read source.
     */
    public static final int STATUS_READ_ERROR = 2;
    /**
     * Unable to fully decode the current frame.
     */
    public static final int STATUS_PARTIAL_DECODE = 3;
    /**
     * max decoder pixel stack size.
     */
    private static final int MAX_STACK_SIZE = 4096;

    /**
     * GIF Disposal Method meaning take no action.
     */
    private static final int DISPOSAL_UNSPECIFIED = 0;
    /**
     * GIF Disposal Method meaning leave canvas from previous frame.
     */
    private static final int DISPOSAL_NONE = 1;
    /**
     * GIF Disposal Method meaning clear canvas to background color.
     */
    private static final int DISPOSAL_BACKGROUND = 2;
    /**
     * GIF Disposal Method meaning clear canvas to frame before last.
     */
    private static final int DISPOSAL_PREVIOUS = 3;

    private static final int NULL_CODE = -1;

    private static final int INITIAL_FRAME_POINTER = -1;

    // Global File Header values and parsing flags.
    // Active color table.
    private int[] act;

    // Raw GIF data from input source.
    private UniRandomReadFile rawData;

    // Raw data read working array.
    private byte[] block;

    // Temporary buffer for block reading. Reads 16k chunks from the native buffer for processing,
    // to greatly reduce JNI overhead.
    private static final int WORK_BUFFER_SIZE = 16384;
    @Nullable
    private byte[] workBuffer;
    private int workBufferSize = 0;
    private int workBufferPosition = 0;

    // LZW decoder working arrays.
    private short[] prefix;
    private byte[] suffix;
    private byte[] pixelStack;
    private byte[] mainPixels;
    private int[] mainScratch;

    private int framePointer;
    private GifHeader header;
    private BitmapProvider bitmapProvider;
    private Bitmap previousImage;
    private boolean savePrevious;
    private int status;
    private int sampleSize;
    private int downsampledHeight;
    private int downsampledWidth;
    private boolean isFirstFrameTransparent;

    /**
     * An interface that can be used to provide reused {@link Bitmap}s to avoid GCs
     * from constantly allocating {@link Bitmap}s for every frame.
     */
    public interface BitmapProvider {
        /**
         * Returns an {@link Bitmap} with exactly the given dimensions and config.
         *
         * @param width  The width in pixels of the desired {@link android.graphics.Bitmap}.
         * @param height The height in pixels of the desired {@link android.graphics.Bitmap}.
         */
        @NonNull
        Bitmap obtain(int width, int height);

        /**
         * Releases the given Bitmap back to the pool.
         */
        void release(Bitmap bitmap);

        /**
         * Returns a byte array used for decoding and generating the frame bitmap.
         *
         * @param size the size of the byte array to obtain
         */
        byte[] obtainByteArray(int size);

        /**
         * Releases the given byte array back to the pool.
         */
        void release(byte[] bytes);

    }

    public GifDecoder(BitmapProvider provider, GifHeader gifHeader, UniRandomReadFile rawData) {
        this(provider, gifHeader, rawData, 1 /*sampleSize*/);
    }

    public GifDecoder(BitmapProvider provider, GifHeader gifHeader, UniRandomReadFile rawData, int sampleSize) {
        this.bitmapProvider = provider;
        setData(gifHeader, rawData, sampleSize);
    }

    public int getWidth() {
        return header.width;
    }

    public int getHeight() {
        return header.height;
    }

    /**
     * Returns the current status of the decoder.
     * <p/>
     * <p> Status will update per frame to allow the caller to tell whether or not the current frame
     * was decoded successfully and/or completely. Format and open failures persist across frames.
     * </p>
     */
    public int getStatus() {
        return status;
    }

    /**
     * Move the animation frame counter forward.
     */
    public void advance() {
        framePointer = (framePointer + 1) % header.frameCount;
    }

    /**
     * Gets display duration for specified frame.
     *
     * @param n int index of frame.
     * @return delay in milliseconds.
     */
    public int getDelay(int n) {
        int delay = -1;
        if ((n >= 0) && (n < header.frameCount)) {
            delay = header.frames.get(n).delay;
        }
        return delay;
    }

    /**
     * Gets display duration for the upcoming frame in ms.
     */
    public int getNextDelay() {
        if (header.frameCount <= 0 || framePointer < 0) {
            return 0;
        }

        return getDelay(framePointer);
    }

    /**
     * Gets the number of frames read from file.
     *
     * @return frame count.
     */
    public int getFrameCount() {
        return header.frameCount;
    }

    /**
     * Gets the current index of the animation frame, or -1 if animation hasn't not yet started.
     *
     * @return frame index.
     */
    public int getCurrentFrameIndex() {
        return framePointer;
    }

    /**
     * Resets the frame pointer to before the 0th frame, as if we'd never used this decoder to
     * decode any frames.
     */
    public void resetFrameIndex() {
        framePointer = INITIAL_FRAME_POINTER;
    }

    /**
     * Gets the "Netscape" iteration count, if any. A count of 0 means repeat indefinitely.
     *
     * @return iteration count if one was specified, else 1.
     */
    public int getLoopCount() {
        return header.loopCount;
    }

    /**
     * Get the next frame in the animation sequence.
     *
     * @return Bitmap representation of frame.
     */
    public synchronized Bitmap getNextFrame() {
        if (header.frameCount <= 0 || framePointer < 0) {
            Log.d(TAG, "unable to decode frame, frameCount=" + header.frameCount + " framePointer=" + framePointer);
            status = STATUS_FORMAT_ERROR;
        }
        if (status == STATUS_FORMAT_ERROR || status == STATUS_READ_ERROR) {
            Log.d(TAG, "Unable to decode frame, status=" + status);
            return null;
        }
        status = STATUS_OK;

        GifFrame currentFrame = header.frames.get(framePointer);
        GifFrame previousFrame = null;
        int previousIndex = framePointer - 1;
        if (previousIndex >= 0) {
            previousFrame = header.frames.get(previousIndex);
        }

        final int savedBgColor = header.bgColor;

        // Set the appropriate color table.
        if (currentFrame.lct == null) {
            act = header.gct;
        } else {
            act = currentFrame.lct;
            if (header.bgIndex == currentFrame.transIndex) {
                header.bgColor = 0;
            }
        }

        int save = 0;
        if (currentFrame.transparency) {
            save = act[currentFrame.transIndex];
            // Set transparent color if specified.
            act[currentFrame.transIndex] = 0;
        }
        if (act == null) {
            Log.d(TAG, "No Valid Color Table");
            // No color table defined.
            status = STATUS_FORMAT_ERROR;
            return null;
        }

        // Transfer pixel data to image.
        Bitmap result = setPixels(currentFrame, previousFrame);

        // Reset the transparent pixel in the color table
        if (currentFrame.transparency) {
            act[currentFrame.transIndex] = save;
        }
        header.bgColor = savedBgColor;

        return result;
    }

    public void clear() {
        header = null;
        mainPixels = null;
        mainScratch = null;
        if (previousImage != null) {
            bitmapProvider.release(previousImage);
        }
        previousImage = null;
        IOUtils.closeQuietly(rawData);
        rawData = null;
        isFirstFrameTransparent = false;
        if (block != null) {
            bitmapProvider.release(block);
        }
        if (workBuffer != null) {
            bitmapProvider.release(workBuffer);
        }
    }

    public synchronized void setData(GifHeader header, UniRandomReadFile file, int sampleSize) {
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("Sample size must be >=0, not: " + sampleSize);
        }
        // Make sure sample size is a power of 2.
        sampleSize = Integer.highestOneBit(sampleSize);
        this.status = STATUS_OK;
        this.header = header;
        isFirstFrameTransparent = false;
        framePointer = INITIAL_FRAME_POINTER;
        // Initialize the raw data buffer.
        rawData = file;

        // No point in specially saving an old frame if we're never going to use it.
        savePrevious = false;
        for (GifFrame frame : header.frames) {
            if (frame.dispose == DISPOSAL_PREVIOUS) {
                savePrevious = true;
                break;
            }
        }

        this.sampleSize = sampleSize;
        // Now that we know the size, init scratch arrays.
        // TODO: Find a way to avoid this entirely or at least downsample it
        // (either should be possible).
        mainPixels = new byte[header.width * header.height];
        mainScratch = new int[(header.width / sampleSize) * (header.height / sampleSize)];
        downsampledWidth = header.width / sampleSize;
        downsampledHeight = header.height / sampleSize;
    }

    /**
     * Creates new frame image from current data (and previous frames as specified by their
     * disposition codes).
     */
    private Bitmap setPixels(GifFrame currentFrame, GifFrame previousFrame) {
        // Final location of blended pixels.
        final int[] dest = mainScratch;

        // fill in starting image contents based on last image's dispose code
        if (previousFrame != null && previousFrame.dispose > DISPOSAL_UNSPECIFIED) {
            // We don't need to do anything for DISPOSAL_NONE, if it has the correct pixels so will our
            // mainScratch and therefore so will our dest array.
            if (previousFrame.dispose == DISPOSAL_BACKGROUND) {
                // Start with a canvas filled with the background color
                int c = 0;
                if (!currentFrame.transparency) {
                    c = header.bgColor;
                } else if (framePointer == 0) {
                    // TODO: We should check and see if all individual pixels are replaced. If they are, the
                    // first frame isn't actually transparent. For now, it's simpler and safer to assume
                    // drawing a transparent background means the GIF contains transparency.
                    isFirstFrameTransparent = true;
                }
                Arrays.fill(dest, c);
            } else if (previousFrame.dispose == DISPOSAL_PREVIOUS && previousImage != null) {
                // Start with the previous frame
                previousImage.getPixels(dest, 0, downsampledWidth, 0, 0, downsampledWidth, downsampledHeight);
            }
        }

        // Decode pixels for this frame  into the global pixels[] scratch.
        decodeBitmapData(currentFrame);

        int downsampledIH = currentFrame.ih / sampleSize;
        int downsampledIY = currentFrame.iy / sampleSize;
        int downsampledIW = currentFrame.iw / sampleSize;
        int downsampledIX = currentFrame.ix / sampleSize;
        // Copy each source line to the appropriate place in the destination.
        int pass = 1;
        int inc = 8;
        int iline = 0;
        boolean isFirstFrame = framePointer == 0;
        for (int i = 0; i < downsampledIH; i++) {
            int line = i;
            if (currentFrame.interlace) {
                if (iline >= downsampledIH) {
                    pass++;
                    switch (pass) {
                        case 2:
                            iline = 4;
                            break;
                        case 3:
                            iline = 2;
                            inc = 4;
                            break;
                        case 4:
                            iline = 1;
                            inc = 2;
                            break;
                        default:
                            break;
                    }
                }
                line = iline;
                iline += inc;
            }
            line += downsampledIY;
            if (line < downsampledHeight) {
                int k = line * downsampledWidth;
                // Start of line in dest.
                int dx = k + downsampledIX;
                // End of dest line.
                int dlim = dx + downsampledIW;
                if (k + downsampledWidth < dlim) {
                    // Past dest edge.
                    dlim = k + downsampledWidth;
                }
                // Start of line in source.
                int sx = i * sampleSize * currentFrame.iw;
                int maxPositionInSource = sx + ((dlim - dx) * sampleSize);
                while (dx < dlim) {
                    // Map color and insert in destination.
                    int averageColor = averageColorsNear(sx, maxPositionInSource, currentFrame.iw);
                    if (averageColor != 0) {
                        dest[dx] = averageColor;
                    } else if (!isFirstFrameTransparent && isFirstFrame) {
                        isFirstFrameTransparent = true;
                    }
                    sx += sampleSize;
                    dx++;
                }
            }
        }

        // Copy pixels into previous image
        if (savePrevious && (currentFrame.dispose == DISPOSAL_UNSPECIFIED || currentFrame.dispose == DISPOSAL_NONE)) {
            if (previousImage == null) {
                previousImage = bitmapProvider.obtain(downsampledWidth, downsampledHeight);
            }
            previousImage.setPixels(dest, 0, downsampledWidth, 0, 0, downsampledWidth, downsampledHeight);
        }

        // Set pixels for current image.
        Bitmap result = bitmapProvider.obtain(downsampledWidth, downsampledHeight);
        result.setPixels(dest, 0, downsampledWidth, 0, 0, downsampledWidth, downsampledHeight);
        return result;
    }

    private int averageColorsNear(int positionInMainPixels, int maxPositionInMainPixels, int currentFrameIw) {
        int alphaSum = 0;
        int redSum = 0;
        int greenSum = 0;
        int blueSum = 0;

        int totalAdded = 0;
        // Find the pixels in the current row.
        for (int i = positionInMainPixels; i < positionInMainPixels + sampleSize && i < mainPixels.length && i < maxPositionInMainPixels; i++) {
            int currentColorIndex = ((int) mainPixels[i]) & 0xff;
            int currentColor = act[currentColorIndex];
            if (currentColor != 0) {
                alphaSum += currentColor >> 24 & 0x000000ff;
                redSum += currentColor >> 16 & 0x000000ff;
                greenSum += currentColor >> 8 & 0x000000ff;
                blueSum += currentColor & 0x000000ff;
                totalAdded++;
            }
        }
        // Find the pixels in the next row.
        for (int i = positionInMainPixels + currentFrameIw; i < positionInMainPixels + currentFrameIw + sampleSize && i < mainPixels.length && i < maxPositionInMainPixels; i++) {
            int currentColorIndex = ((int) mainPixels[i]) & 0xff;
            int currentColor = act[currentColorIndex];
            if (currentColor != 0) {
                alphaSum += currentColor >> 24 & 0x000000ff;
                redSum += currentColor >> 16 & 0x000000ff;
                greenSum += currentColor >> 8 & 0x000000ff;
                blueSum += currentColor & 0x000000ff;
                totalAdded++;
            }
        }
        if (totalAdded == 0) {
            return 0;
        } else {
            return ((alphaSum / totalAdded) << 24) | ((redSum / totalAdded) << 16) | ((greenSum / totalAdded) << 8) | (blueSum / totalAdded);
        }
    }

    /**
     * Decodes LZW image data into pixel array. Adapted from John Cristy's BitmapMagick.
     */
    private void decodeBitmapData(GifFrame frame) {
        workBufferSize = 0;
        workBufferPosition = 0;
        if (frame != null) {
            // Jump to the frame start position.
            try {
                rawData.seek(frame.bufferFrameStart);
            } catch (IOException e) {
                e.printStackTrace(); // TODO what if get exception
            }
        }

        int npix = (frame == null) ? header.width * header.height : frame.iw * frame.ih;
        int available, clear, codeMask, codeSize, endOfInformation, inCode, oldCode, bits, code, count,
                i, datum,
                dataSize, first, top, bi, pi;

        if (mainPixels == null || mainPixels.length < npix) {
            // Allocate new pixel array.
            mainPixels = new byte[npix];
        }
        if (prefix == null) {
            prefix = new short[MAX_STACK_SIZE];
        }
        if (suffix == null) {
            suffix = new byte[MAX_STACK_SIZE];
        }
        if (pixelStack == null) {
            pixelStack = new byte[MAX_STACK_SIZE + 1];
        }

        // Initialize GIF data stream decoder.
        dataSize = readByte();
        clear = 1 << dataSize;
        endOfInformation = clear + 1;
        available = clear + 2;
        oldCode = NULL_CODE;
        codeSize = dataSize + 1;
        codeMask = (1 << codeSize) - 1;
        for (code = 0; code < clear; code++) {
            // XXX ArrayIndexOutOfBoundsException.
            prefix[code] = 0;
            suffix[code] = (byte) code;
        }

        // Decode GIF pixel stream.
        datum = bits = count = first = top = pi = bi = 0;
        for (i = 0; i < npix; ) {
            // Load bytes until there are enough bits for a code.
            if (count == 0) {
                // Read a new data block.
                count = readBlock();
                if (count <= 0) {
                    status = STATUS_PARTIAL_DECODE;
                    break;
                }
                bi = 0;
            }

            datum += (((int) block[bi]) & 0xff) << bits;
            bits += 8;
            bi++;
            count--;

            while (bits >= codeSize) {
                // Get the next code.
                code = datum & codeMask;
                datum >>= codeSize;
                bits -= codeSize;

                // Interpret the code.
                if (code == clear) {
                    // Reset decoder.
                    codeSize = dataSize + 1;
                    codeMask = (1 << codeSize) - 1;
                    available = clear + 2;
                    oldCode = NULL_CODE;
                    continue;
                }

                if (code > available) {
                    status = STATUS_PARTIAL_DECODE;
                    break;
                }

                if (code == endOfInformation) {
                    break;
                }

                if (oldCode == NULL_CODE) {
                    pixelStack[top++] = suffix[code];
                    oldCode = code;
                    first = code;
                    continue;
                }
                inCode = code;
                if (code >= available) {
                    pixelStack[top++] = (byte) first;
                    code = oldCode;
                }
                while (code >= clear) {
                    pixelStack[top++] = suffix[code];
                    code = prefix[code];
                }
                first = ((int) suffix[code]) & 0xff;
                pixelStack[top++] = (byte) first;

                // Add a new string to the string table.
                if (available < MAX_STACK_SIZE) {
                    prefix[available] = (short) oldCode;
                    suffix[available] = (byte) first;
                    available++;
                    if (((available & codeMask) == 0) && (available < MAX_STACK_SIZE)) {
                        codeSize++;
                        codeMask += available;
                    }
                }
                oldCode = inCode;

                while (top > 0) {
                    // Pop a pixel off the pixel stack.
                    mainPixels[pi++] = pixelStack[--top];
                    i++;
                }
            }
        }

        // Clear missing pixels.
        for (i = pi; i < npix; i++) {
            mainPixels[i] = 0;
        }
    }

    /**
     * Reads the next chunk for the intermediate work buffer.
     */
    private void readChunkIfNeeded() throws IOException {
        if (workBufferSize > workBufferPosition) {
            return;
        }
        if (workBuffer == null) {
            workBuffer = bitmapProvider.obtainByteArray(WORK_BUFFER_SIZE);
        }
        workBufferPosition = 0;
        workBufferSize = Math.min((int) rawData.remaining(), WORK_BUFFER_SIZE);
        rawData.read(workBuffer, 0, workBufferSize);
    }

    /**
     * Reads a single byte from the input stream.
     */
    private int readByte() {
        try {
            readChunkIfNeeded();
            return workBuffer[workBufferPosition++] & 0xFF;
        } catch (Exception e) {
            e.printStackTrace();
            status = STATUS_FORMAT_ERROR;
            return 0;
        }
    }

    /**
     * Reads next variable length block from input.
     *
     * @return number of bytes stored in "buffer".
     */
    private int readBlock() {
        int blockSize = readByte();
        if (blockSize > 0) {
            try {
                if (block == null) {
                    block = bitmapProvider.obtainByteArray(255);
                }
                final int remaining = workBufferSize - workBufferPosition;
                if (remaining >= blockSize) {
                    // Block can be read from the current work buffer.
                    System.arraycopy(workBuffer, workBufferPosition, block, 0, blockSize);
                    workBufferPosition += blockSize;
                } else if (rawData.remaining() + remaining >= blockSize) {
                    // Block can be read in two passes.
                    System.arraycopy(workBuffer, workBufferPosition, block, 0, remaining);
                    workBufferPosition = workBufferSize;
                    readChunkIfNeeded();
                    final int secondHalfRemaining = blockSize - remaining;
                    System.arraycopy(workBuffer, 0, block, remaining, secondHalfRemaining);
                    workBufferPosition += secondHalfRemaining;
                } else {
                    Log.d(TAG, "Can't read block");
                    status = STATUS_FORMAT_ERROR;
                }
            } catch (Exception e) {
                Log.w(TAG, "Error Reading Block", e);
                status = STATUS_FORMAT_ERROR;
            }
        }
        return blockSize;
    }
}
