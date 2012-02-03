/*
 * Copyright (C) 2010, 2011 Christopher Eby <kreed@kreed.org>
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

package org.adonai.nolife;

import java.io.File;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.adonai.nolife.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.Toast;

/**
 * Base activity for activities that contain playback controls. Handles
 * communication with the PlaybackService and response to state and song
 * changes.
 */
public class PlaybackActivity extends Activity
	implements Handler.Callback,
	           View.OnClickListener,
	           CoverView.Callback
{
	enum Action {
		Nothing,
		Library,
		PlayPause,
		NextSong,
		PreviousSong,
		Repeat,
		Shuffle,
		EnqueueAlbum,
		EnqueueArtist,
		EnqueueGenre,
		ClearQueue,
		ToggleControls,
		ShowLyrics,
	}

	private Action mUpAction;
	private Action mDownAction;

	protected Handler mHandler;
	protected Looper mLooper;

	protected CoverView mCoverView;
	protected ImageButton mPlayPauseButton;

	protected int mState;
	private long mLastStateEvent;
	private long mLastSongEvent;

	@Override
	public void onCreate(Bundle state)
	{
		super.onCreate(state);

		PlaybackService.addActivity(this);

		setVolumeControlStream(AudioManager.STREAM_MUSIC);

		HandlerThread thread = new HandlerThread(getClass().getName());
		thread.start();

		mLooper = thread.getLooper();
		mHandler = new Handler(mLooper, this);
	}

	@Override
	public void onDestroy()
	{
		PlaybackService.removeActivity(this);
		mLooper.quit();
		super.onDestroy();
	}

	/**
	 * Retrieve an action from the given SharedPreferences.
	 *
	 * @param prefs The SharedPreferences instance to load from.
	 * @param key The preference key.
	 * @param def The value to return if the key is not found or cannot be loaded.
	 * @return An action
	 */
	public static Action getAction(SharedPreferences prefs, String key, Action def)
	{
		try {
			String pref = prefs.getString(key, null);
			if (pref == null)
				return def;
			return Enum.valueOf(Action.class, pref);
		} catch (Exception e) {
			return def;
		}
	}

	@Override
	public void onStart()
	{
		super.onStart();

		if (PlaybackService.hasInstance())
			onServiceReady();
		else
			startService(new Intent(this, PlaybackService.class));

		SharedPreferences prefs = PlaybackService.getSettings(this);
		mUpAction = getAction(prefs, "swipe_up_action", Action.Nothing);
		mDownAction = getAction(prefs, "swipe_down_action", Action.Nothing);

		Window window = getWindow();
		if (prefs.getBoolean("disable_lockscreen", false))
			window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
		else
			window.clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
	}

	@Override
	public void onResume()
	{
		super.onResume();
		MediaButtonReceiver.registerMediaButton(this);
		MediaButtonReceiver.setInCall(false);
		if (PlaybackService.hasInstance()) {
			PlaybackService service = PlaybackService.get(this);
			service.userActionTriggered();
		}
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event)
	{
		switch (keyCode) {
		case KeyEvent.KEYCODE_HEADSETHOOK:
		case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
		case KeyEvent.KEYCODE_MEDIA_NEXT:
		case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
			return MediaButtonReceiver.processKey(this, event);
		}

		return super.onKeyDown(keyCode, event);
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event)
	{
		switch (keyCode) {
		case KeyEvent.KEYCODE_HEADSETHOOK:
		case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
		case KeyEvent.KEYCODE_MEDIA_NEXT:
		case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
			return MediaButtonReceiver.processKey(this, event);
		}

		return super.onKeyUp(keyCode, event);
	}

	public void nextSong()
	{
		setSong(PlaybackService.get(this).nextSong());
	}

	public void previousSong(boolean strict)
	{
		setSong(PlaybackService.get(this).previousOrToStartSong(strict));
	}

	public void playPause()
	{
		PlaybackService service = PlaybackService.get(this);
		int state = service.playPause();
		if ((state & PlaybackService.FLAG_ERROR) != 0)
			Toast.makeText(this, service.getErrorMessage(), Toast.LENGTH_LONG).show();
		setState(state);
	}

	@Override
	public void onClick(View view)
	{
		switch (view.getId()) {
		case R.id.next:
			nextSong();
			break;
		case R.id.play_pause:
			playPause();
			break;
		case R.id.previous:
			previousSong(false);
			break;
		case R.id.seek_head:
			PlaybackService.get(this).seekForward();
			break;
		case R.id.seek_back:
			PlaybackService.get(this).seekBackward();
			break;
		}
	}

	/**
	 * Called when the PlaybackService state has changed.
	 *
	 * @param state PlaybackService state
	 * @param toggled The flags that have changed from the previous state
	 */
	protected void onStateChange(int state, int toggled)
	{
		if ((toggled & PlaybackService.FLAG_PLAYING) != 0 && mPlayPauseButton != null)
			mPlayPauseButton.setImageResource((state & PlaybackService.FLAG_PLAYING) == 0 ? R.drawable.play : R.drawable.pause);
	}

	protected void setState(final int state)
	{
		mLastStateEvent = SystemClock.uptimeMillis();

		if (mState != state) {
			final int toggled = mState ^ state;
			mState = state;
			runOnUiThread(new Runnable() {
				public void run()
				{
					onStateChange(state, toggled);
				}
			});
		}
	}

	/**
	 * Called by PlaybackService to update the state.
	 */
	public void setState(long uptime, int state)
	{
		if (uptime > mLastStateEvent)
			setState(state);
	}

	/**
	 * Sets up components when the PlaybackService is initialized and available to
	 * interact with. Override to implement further post-initialization behavior.
	 */
	protected void onServiceReady()
	{
		PlaybackService service = PlaybackService.get(this);
		setSong(service.getSong(0));
		setState(service.getState());
	}

	/**
	 * Called when the current song changes.
	 *
	 * @param song The new song
	 */
	protected void onSongChange(Song song)
	{
		if (mCoverView != null)
			mCoverView.querySongs(PlaybackService.get(this));
	}

	protected void setSong(final Song song)
	{
		mLastSongEvent = SystemClock.uptimeMillis();
		runOnUiThread(new Runnable() {
			public void run()
			{
				onSongChange(song);
			}
		});
	}

	/**
	 * Called by PlaybackService to update the current song.
	 */
	public void setSong(long uptime, Song song)
	{
		if (uptime > mLastSongEvent)
			setSong(song);
	}

	/**
	 * Called by PlaybackService to update an active song (next, previous, or
	 * current).
	 */
	public void replaceSong(int delta, Song song)
	{
		if (mCoverView != null)
			mCoverView.setSong(delta + 1, song);
	}

	/**
	 * Called when the content of the media store has changed.
	 */
	public void onMediaChange()
	{
	}

	static final int MENU_PREFS = 2;
	static final int MENU_LIBRARY = 3;
	static final int MENU_GET_LYRICS = 4;
	static final int MENU_PLAYBACK = 5;
	static final int MENU_SEARCH = 7;
	static final int MENU_RELOAD_SONGS = 8;
	static final int MENU_PICK_FOLDER = 6;
	static final int MENU_CUT_SONG = 9;
	static final int MENU_MORE = 10;
	static final int MENU_EDIT_SONG = 11;
	static final int MENU_DELETE = 12;
	static final int MENU_FULL_DELETE = 120;
	static final int MENU_PLAYLIST_DELETE = 121;

	@Override
	public boolean onCreateOptionsMenu(Menu menu)
	{
		menu.add(0, MENU_PREFS, 0, R.string.settings).setIcon(R.drawable.ic_menu_preferences);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		switch (item.getItemId()) {
		case MENU_PREFS:
			startActivity(new Intent(this, PreferencesActivity.class));
			return true;
		default:
			return false;
		}
	}

	@Override
	public boolean handleMessage(Message msg)
	{
		return false;
	}

	/**
	 * Cycle shuffle mode.
	 */
	public void cycleShuffle()
	{
		setState(PlaybackService.get(this).cycleShuffle());
	}

	/**
	 * Cycle the finish action.
	 */
	public void cycleFinishAction()
	{
		setState(PlaybackService.get(this).cycleFinishAction());
	}

	/**
	 * Open the library activity.
	 */
	public void openLibrary()
	{
		startActivity(new Intent(this, LibraryActivity.class));
	}

	public void enqueue(int type)
	{
		PlaybackService.get(this).enqueueFromCurrent(type);
	}

	public void performAction(Action action)
	{
		switch (action) {
		case Nothing:
			break;
		case Library:
			openLibrary();
			break;
		case PlayPause:
			playPause();
			break;
		case NextSong:
			nextSong();
			break;
		case PreviousSong:
			previousSong(false);
			break;
		case Repeat:
			cycleFinishAction();
			break;
		case Shuffle:
			cycleShuffle();
			break;
		case EnqueueAlbum:
			enqueue(MediaUtils.TYPE_ALBUM);
			break;
		case EnqueueArtist:
			enqueue(MediaUtils.TYPE_ARTIST);
			break;
		case EnqueueGenre:
			enqueue(MediaUtils.TYPE_GENRE);
			break;
		case ClearQueue:
			PlaybackService.get(this).clearQueue();
			Toast.makeText(this, R.string.queue_cleared, Toast.LENGTH_SHORT).show();
			break;
		case ShowLyrics:
			long id = PlaybackService.get(this).getSong(0).id;
			ContentResolver resolver = getContentResolver();
			String[] projection = new String [] { MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA };
			Cursor cursor = MediaUtils.buildQuery(MediaUtils.TYPE_SONG, id, projection, null).runQuery(resolver);
			
			if (cursor != null) {
				while (cursor.moveToNext()) {
					File SongFile = new File(cursor.getString(1));
					try {
						AudioFile mf = AudioFileIO.read(SongFile);
						Tag tag = mf.getTagOrCreateDefault();
						
						AlertDialog alertDialog;
						alertDialog = new AlertDialog.Builder(this).create();
						alertDialog.setTitle(getResources().getString(R.string.song_lyrics));
						alertDialog.setMessage(tag.getFirst(FieldKey.LYRICS));
						alertDialog.show();
						
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
				cursor.close();
			}
			break;
		default:
			throw new IllegalArgumentException("Invalid action: " + action);
		}
	}

	@Override
	public void upSwipe()
	{
		performAction(mUpAction);
	}

	@Override
	public void downSwipe()
	{
		performAction(mDownAction);
	}
}
