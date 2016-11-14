/*
 * MIT License
 *
 * Copyright (c) 2016 Knowledge, education for life.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package life.knowledge4.videotrimmer.utils;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.NonNull;
import android.util.Log;

import com.coremedia.iso.boxes.Container;
import com.googlecode.mp4parser.FileDataSourceViaHeapImpl;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder;
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator;
import com.googlecode.mp4parser.authoring.tracks.AppendTrack;
import com.googlecode.mp4parser.authoring.tracks.CroppedTrack;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Formatter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import life.knowledge4.videotrimmer.interfaces.OnTrimVideoListener;

public class TrimVideoUtils {

    private static final String TAG = TrimVideoUtils.class.getSimpleName();

	private static final int DEFAULT_BUFFER_SIZE = 1 * 1024 * 1024;

	public static final boolean HAS_MEDIA_MUXER =
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2;

	public static void startTrim(File src, File dst, int startMs, int endMs, @NonNull OnTrimVideoListener callback)
			throws IOException {
		if (HAS_MEDIA_MUXER) {
			genVideoUsingMuxer(src.getPath(), dst.getPath(), startMs, endMs,
					true, true, callback);
		} else {
			genVideoUsingMp4Parser(src, dst, startMs, endMs, callback);
		}
	}



   /* @SuppressWarnings("ResultOfMethodCallIgnored")
>>>>>>> Stashed changes
    public static void startTrim(@NonNull File src, @NonNull String dst, long startMs, long endMs, @NonNull OnTrimVideoListener callback) throws IOException {
        final String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        final String fileName = "MP4_" + timeStamp + ".mp4";
        final String filePath = dst + fileName;

        File file = new File(filePath);
        file.getParentFile().mkdirs();
        Log.d(TAG, "Generated file path " + filePath);
        genVideoUsingMp4Parser(src, file, startMs, endMs, callback);
    }*/


	/**
	 * @param srcPath the path of source video file.
	 * @param dstPath the path of destination video file.
	 * @param startMs starting time in milliseconds for trimming. Set to
	 *            negative if starting from beginning.
	 * @param endMs end time for trimming in milliseconds. Set to negative if
	 *            no trimming at the end.
	 * @param useAudio true if keep the audio track from the source.
	 * @param useVideo true if keep the video track from the source
	 * @throws IOException
	 */
	@TargetApi(18)
	private static void genVideoUsingMuxer(String srcPath, String dstPath,
										   int startMs, int endMs, boolean useAudio, boolean useVideo, @NonNull OnTrimVideoListener callback)
			throws IOException {
		// Set up MediaExtractor to read from the source.
		MediaExtractor extractor = new MediaExtractor();
		extractor.setDataSource(srcPath);

		int trackCount = extractor.getTrackCount();

		// Set up MediaMuxer for the destination.
		MediaMuxer muxer;
		muxer = new MediaMuxer(dstPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

		// Set up the tracks and retrieve the max buffer size for selected
		// tracks.
		HashMap<Integer, Integer> indexMap = new HashMap<Integer,
				Integer>(trackCount);
		int bufferSize = -1;
		for (int i = 0; i < trackCount; i++) {
			MediaFormat format = extractor.getTrackFormat(i);
			String mime = format.getString(MediaFormat.KEY_MIME);

			boolean selectCurrentTrack = false;

			if (mime.startsWith("audio/") && useAudio) {
				selectCurrentTrack = true;
			} else if (mime.startsWith("video/") && useVideo) {
				selectCurrentTrack = true;
			}

			if (selectCurrentTrack) {
				extractor.selectTrack(i);
				int dstIndex = muxer.addTrack(format);
				indexMap.put(i, dstIndex);
				if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
					int newSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
					bufferSize = newSize > bufferSize ? newSize : bufferSize;
				}
			}
		}

		if (bufferSize < 0) {
			bufferSize = DEFAULT_BUFFER_SIZE;
		}

		// Set up the orientation and starting time for extractor.
		MediaMetadataRetriever retrieverSrc = new MediaMetadataRetriever();
		retrieverSrc.setDataSource(srcPath);
		String degreesString = retrieverSrc.extractMetadata(
				MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
		if (degreesString != null) {
			int degrees = Integer.parseInt(degreesString);
			if (degrees >= 0) {
				muxer.setOrientationHint(degrees);
			}
		}

		if (startMs > 0) {
			extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
		}

		// Copy the samples from MediaExtractor to MediaMuxer. We will loop
		// for copying each sample and stop when we get to the end of the source
		// file or exceed the end time of the trimming.
		int offset = 0;
		int trackIndex = -1;
		ByteBuffer dstBuf = ByteBuffer.allocate(bufferSize);
		MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
		try {
			muxer.start();
			while (true) {
				bufferInfo.offset = offset;
				bufferInfo.size = extractor.readSampleData(dstBuf, offset);
				if (bufferInfo.size < 0) {
					Log.d(TAG, "Saw input EOS.");
					bufferInfo.size = 0;
					break;
				} else {
					bufferInfo.presentationTimeUs = extractor.getSampleTime();
					if (endMs > 0 && bufferInfo.presentationTimeUs > (endMs * 1000)) {
						Log.d(TAG, "The current sample is over the trim end time.");
						break;
					} else {
						bufferInfo.flags = extractor.getSampleFlags();
						trackIndex = extractor.getSampleTrackIndex();

						muxer.writeSampleData(indexMap.get(trackIndex), dstBuf,
								bufferInfo);
						extractor.advance();
					}
				}
			}

			muxer.stop();
		} catch (IllegalStateException e) {
			// Swallow the exception due to malformed source.
			Log.w(TAG, "The source video file is malformed");
		} finally {
			muxer.release();
			if (callback != null)
				callback.getResult(Uri.parse(dstPath.toString()));
		}
		return;
	}


	@SuppressWarnings("ResultOfMethodCallIgnored")
    private static void genVideoUsingMp4Parser(@NonNull File src, @NonNull File dst, long startMs, long endMs, @NonNull OnTrimVideoListener callback) throws IOException {
        // NOTE: Switched to using FileDataSourceViaHeapImpl since it does not use memory mapping (VM).
        // Otherwise we get OOM with large movie files.
        Movie movie = MovieCreator.build(new FileDataSourceViaHeapImpl(src.getAbsolutePath()));

        List<Track> tracks = movie.getTracks();
        movie.setTracks(new LinkedList<Track>());
        // remove all tracks we will create new tracks from the old

        double startTime1 = startMs / 1000;
        double endTime1 = endMs / 1000;

        boolean timeCorrected = false;

        // Here we try to find a track that has sync samples. Since we can only start decoding
        // at such a sample we SHOULD make sure that the start of the new fragment is exactly
        // such a frame
        for (Track track : tracks) {
            if (track.getSyncSamples() != null && track.getSyncSamples().length > 0) {
                if (timeCorrected) {
                    // This exception here could be a false positive in case we have multiple tracks
                    // with sync samples at exactly the same positions. E.g. a single movie containing
                    // multiple qualities of the same video (Microsoft Smooth Streaming file)

                    throw new RuntimeException("The startTime has already been corrected by another track with SyncSample. Not Supported.");
                }
                startTime1 = correctTimeToSyncSample(track, startTime1, false);
                endTime1 = correctTimeToSyncSample(track, endTime1, true);
                timeCorrected = true;
            }
        }

        for (Track track : tracks) {
            long currentSample = 0;
            double currentTime = 0;
            double lastTime = -1;
            long startSample1 = -1;
            long endSample1 = -1;

            for (int i = 0; i < track.getSampleDurations().length; i++) {
                long delta = track.getSampleDurations()[i];


                if (currentTime > lastTime && currentTime <= startTime1) {
                    // current sample is still before the new starttime
                    startSample1 = currentSample;
                }
                if (currentTime > lastTime && currentTime <= endTime1) {
                    // current sample is after the new start time and still before the new endtime
                    endSample1 = currentSample;
                }
                lastTime = currentTime;
                currentTime += (double) delta / (double) track.getTrackMetaData().getTimescale();
                currentSample++;
            }
            movie.addTrack(new AppendTrack(new CroppedTrack(track, startSample1, endSample1)));
        }

        dst.getParentFile().mkdirs();

        if (!dst.exists()) {
            dst.createNewFile();
        }

        Container out = new DefaultMp4Builder().build(movie);

        FileOutputStream fos = new FileOutputStream(dst);
        FileChannel fc = fos.getChannel();
        out.writeContainer(fc);

        fc.close();
        fos.close();
        if (callback != null)
            callback.getResult(Uri.parse(dst.toString()));
    }

    private static double correctTimeToSyncSample(@NonNull Track track, double cutHere, boolean next) {
        double[] timeOfSyncSamples = new double[track.getSyncSamples().length];
        long currentSample = 0;
        double currentTime = 0;
        for (int i = 0; i < track.getSampleDurations().length; i++) {
            long delta = track.getSampleDurations()[i];

            if (Arrays.binarySearch(track.getSyncSamples(), currentSample + 1) >= 0) {
                // samples always start with 1 but we start with zero therefore +1
                timeOfSyncSamples[Arrays.binarySearch(track.getSyncSamples(), currentSample + 1)] = currentTime;
            }
            currentTime += (double) delta / (double) track.getTrackMetaData().getTimescale();
            currentSample++;

        }
        double previous = 0;
        for (double timeOfSyncSample : timeOfSyncSamples) {
            if (timeOfSyncSample > cutHere) {
                if (next) {
                    return timeOfSyncSample;
                } else {
                    return previous;
                }
            }
            previous = timeOfSyncSample;
        }
        return timeOfSyncSamples[timeOfSyncSamples.length - 1];
    }

    public static String stringForTime(int timeMs) {
        int totalSeconds = timeMs / 1000;

        int seconds = totalSeconds % 60;
        int minutes = (totalSeconds / 60) % 60;
        int hours = totalSeconds / 3600;

        Formatter mFormatter = new Formatter();
        if (hours > 0) {
            return mFormatter.format("%d:%02d:%02d", hours, minutes, seconds).toString();
        } else {
            return mFormatter.format("%02d:%02d", minutes, seconds).toString();
        }
    }
}
