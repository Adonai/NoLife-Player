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

import org.adonai.nolife.R;

import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;

/**
 * 2x4 widget that shows title, artist, album art, a play/pause button, and a
 * next button.
 */
public class FourLongWidget extends AppWidgetProvider {
	private static boolean sEnabled;

	@Override
	public void onEnabled(Context context)
	{
		sEnabled = true;
	}

	@Override
	public void onDisabled(Context context)
	{
		sEnabled = false;
	}

	@Override
	public void onUpdate(Context context, AppWidgetManager manager, int[] ids)
	{
		Song song = null;
		int state = 0;

		if (PlaybackService.hasInstance()) {
			PlaybackService service = PlaybackService.get(context);
			song = service.getSong(0);
			state = service.getState();
		}

		sEnabled = true;
		updateWidget(context, manager, song, state);
	}

	/**
	 * Check if there are any instances of this widget placed.
	 */
	public static void checkEnabled(Context context, AppWidgetManager manager)
	{
		sEnabled = manager.getAppWidgetIds(new ComponentName(context, FourLongWidget.class)).length != 0;
	}

	/**
	 * Populate the widgets with the given ids with the given info.
	 *
	 * @param context A Context to use.
	 * @param manager The AppWidgetManager that will be used to update the
	 * widget.
	 * @param song The current Song in PlaybackService.
	 * @param state The current PlaybackService state.
	 */
	public static void updateWidget(Context context, AppWidgetManager manager, Song song, int state)
	{
		if (!sEnabled)
			return;
		
		RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.four_long_widget);
		SharedPreferences settings = PlaybackService.getSettings(context);
		
		if(!settings.getBoolean("widget_transparency", true))
			views.setInt(R.id.widgetLayout, "setBackgroundResource", R.drawable.appwidget_bg);
		else
			views.setInt(R.id.widgetLayout, "setBackgroundResource", R.drawable.alt_appwidget_bg);
		
		if ((state & PlaybackService.FLAG_NO_MEDIA) != 0) {
			views.setViewVisibility(R.id.buttons, View.GONE);
			views.setViewVisibility(R.id.title, View.GONE);
			views.setInt(R.id.artist, "setText", R.string.no_songs);
			views.setImageViewResource(R.id.cover, 0);
		} else if (song == null) {
			views.setViewVisibility(R.id.buttons, View.VISIBLE);
			views.setViewVisibility(R.id.title, View.GONE);
			views.setInt(R.id.artist, "setText", R.string.app_name);
			views.setImageViewResource(R.id.cover, 0);
		} else {
			views.setViewVisibility(R.id.title, View.VISIBLE);
			views.setViewVisibility(R.id.buttons, View.VISIBLE);
			views.setTextViewText(R.id.title, song.title);
			views.setTextViewText(R.id.artist, song.artist);
			Uri uri = song.getCoverUri();
			if (uri == null)
				views.setImageViewResource(R.id.cover, 0);
			else
				views.setImageViewUri(R.id.cover, uri);
		}

		boolean playing = (state & PlaybackService.FLAG_PLAYING) != 0;
		boolean shuffle = (state & PlaybackService.MASK_SHUFFLE) != 0;
		boolean repeat = (((state & PlaybackService.MASK_FINISH) >> PlaybackService.SHIFT_FINISH) & SongTimeline.FINISH_REPEAT_CURRENT) != 0;
		AudioManager am = (AudioManager)context.getApplicationContext().getSystemService(Service.AUDIO_SERVICE);
		
		views.setImageViewResource(R.id.play_pause, playing ? R.drawable.pause : R.drawable.play);
		views.setImageViewResource(R.id.shuffle_ind, shuffle ? R.drawable.shuffle_active : R.drawable.shuffle_inactive);
		views.setImageViewResource(R.id.repeat_ind, repeat ? R.drawable.repeat_active : R.drawable.repeat_inactive);
		views.setProgressBar(R.id.volumebar, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), am.getStreamVolume(AudioManager.STREAM_MUSIC), false);
		
		Intent intent;
		PendingIntent pendingIntent;

		ComponentName service = new ComponentName(context, PlaybackService.class);

		intent = new Intent(context, LaunchActivity.class);
		pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);
		views.setOnClickPendingIntent(R.id.cover, pendingIntent);
		views.setOnClickPendingIntent(R.id.text, pendingIntent);

		intent = new Intent(PlaybackService.ACTION_TOGGLE_PLAYBACK).setComponent(service);
		pendingIntent = PendingIntent.getService(context, 0, intent, 0);
		views.setOnClickPendingIntent(R.id.play_pause, pendingIntent);

		intent = new Intent(PlaybackService.ACTION_NEXT_SONG).setComponent(service);
		pendingIntent = PendingIntent.getService(context, 0, intent, 0);
		views.setOnClickPendingIntent(R.id.next, pendingIntent);
		
		intent = new Intent(PlaybackService.ACTION_PREVIOUS_SONG).setComponent(service);
		pendingIntent = PendingIntent.getService(context, 0, intent, 0);
		views.setOnClickPendingIntent(R.id.previous, pendingIntent);
		
		intent = new Intent(PlaybackService.ACTION_VOLUME_UP).setComponent(service);
		pendingIntent = PendingIntent.getService(context, 0, intent, 0);
		views.setOnClickPendingIntent(R.id.adjustvol_up, pendingIntent);
		
		intent = new Intent(PlaybackService.ACTION_VOLUME_DOWN).setComponent(service);
		pendingIntent = PendingIntent.getService(context, 0, intent, 0);
		views.setOnClickPendingIntent(R.id.adjustvol_down, pendingIntent);
		
		intent = new Intent(PlaybackService.ACTION_SEEK_FORWARD).setComponent(service);
		pendingIntent = PendingIntent.getService(context, 0, intent, 0);
		views.setOnClickPendingIntent(R.id.seek_head, pendingIntent);
		
		intent = new Intent(PlaybackService.ACTION_SEEK_BACKWARD).setComponent(service);
		pendingIntent = PendingIntent.getService(context, 0, intent, 0);
		views.setOnClickPendingIntent(R.id.seek_back, pendingIntent);

		manager.updateAppWidget(new ComponentName(context, FourLongWidget.class), views);
	}
}
