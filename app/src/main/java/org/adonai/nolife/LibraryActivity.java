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

import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.PaintDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TabWidget;
import android.widget.TextView;
import android.widget.Toast;

import junit.framework.Assert;

import java.io.File;

/**
 * The library activity where songs to play can be selected from the library.
 */
public class LibraryActivity extends PlaybackActivity implements AdapterView.OnItemClickListener, TextWatcher {

    private static final int ACTION_PLAY = 0;
    private static final int ACTION_ENQUEUE = 1;
    private static final int ACTION_LAST_USED = 2;
    private static final int[] modeForAction =
        { SongTimeline.MODE_PLAY, SongTimeline.MODE_ENQUEUE };

    private TabWidget mTabWidget;
    private ViewGroup mLists;
    private int mCurrentTab;

    private View mSearchBox;
    private boolean mSearchBoxVisible;
    private TextView mTextFilter;
    private View mClearButton;

    private View mControls;
    private TextView mStatusText;
    private ImageView mCover;

    private ViewGroup mLimiterViews;

    private int mDefaultAction;

    private int mLastAction = ACTION_PLAY;
    private long mLastActedId;

    private MediaAdapter[] mAdapters;
    private MediaAdapter mArtistAdapter;
    private MediaAdapter mAlbumAdapter;
    private MediaAdapter mSongAdapter;
    private MediaAdapter mPlaylistAdapter;
    private MediaAdapter mGenreAdapter;
    private MediaAdapter mCurrentAdapter;

    private final ContentObserver mPlaylistObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange)
        {
            mUiHandler.sendMessage(mUiHandler.obtainMessage(CONTENT_CHANGED, mCurrentAdapter));
        }
    };

    @Override
    public void onCreate(Bundle state)
    {
        super.onCreate(state);

        MediaView.init(this);

        SharedPreferences settings = PlaybackService.getSettings(this);
        if (settings.getBoolean("controls_in_selector", false)) {
            setContentView(R.layout.library_withcontrols);

            mControls = findViewById(R.id.controls);

            mStatusText = (TextView)mControls.findViewById(R.id.status_text);
            mCover = (ImageView)mControls.findViewById(R.id.cover);
            View previous = mControls.findViewById(R.id.previous);
            mPlayPauseButton = (ImageButton)mControls.findViewById(R.id.play_pause);
            View next = mControls.findViewById(R.id.next);

            View seek_forward = mControls.findViewById(R.id.seek_head);
            View seek_backward = mControls.findViewById(R.id.seek_back);

            mCover.setOnClickListener(this);
            previous.setOnClickListener(this);
            mPlayPauseButton.setOnClickListener(this);
            next.setOnClickListener(this);
            seek_forward.setOnClickListener(this);
            seek_backward.setOnClickListener(this);
        } else {
            setContentView(R.layout.library_nocontrols);
        }

        mSearchBox = findViewById(R.id.search_box);

        mTextFilter = (TextView)findViewById(R.id.filter_text);
        mTextFilter.addTextChangedListener(this);

        mClearButton = findViewById(R.id.clear_button);
        mClearButton.setOnClickListener(this);

        mLimiterViews = (ViewGroup)findViewById(R.id.limiter_layout);

        mArtistAdapter = setupView(R.id.artist_list, MediaUtils.TYPE_ARTIST, true, true, null);
        mAlbumAdapter = setupView(R.id.album_list, MediaUtils.TYPE_ALBUM, true, true, state == null ? null : (MediaAdapter.Limiter)state.getSerializable("limiter_albums"));
        mSongAdapter = setupView(R.id.song_list, MediaUtils.TYPE_SONG, false, true, state == null ? null : (MediaAdapter.Limiter)state.getSerializable("limiter_songs"));
        mPlaylistAdapter = setupView(R.id.playlist_list, MediaUtils.TYPE_PLAYLIST, true, true, null);
        mPlaylistAdapter.setHeaderText(getResources().getString(R.string.current_playlist));
        mGenreAdapter = setupView(R.id.genre_list, MediaUtils.TYPE_GENRE, true, false, state == null ? null : (MediaAdapter.Limiter)state.getSerializable("limiter_genres"));
        // These should be in the same order as MediaUtils.TYPE_*
        mAdapters = new MediaAdapter[] { mArtistAdapter, mAlbumAdapter, mSongAdapter, mPlaylistAdapter, mGenreAdapter };

        mLists = (ViewGroup)findViewById(R.id.lists);
        TabWidget tabWidget = (TabWidget)findViewById(R.id.tab_widget);

        mTabWidget = tabWidget;
        for (int i = 0, count = tabWidget.getTabCount(); i != count; ++i) {
            View view = tabWidget.getChildTabViewAt(i);
            view.setOnClickListener(this);
            view.setTag(i);
        }

        getContentResolver().registerContentObserver(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, true, mPlaylistObserver);

        int currentTab = 2;

        if (state != null) {
            if (state.getBoolean("search_box_visible"))
                setSearchBoxVisible(true);
            currentTab = state.getInt("current_tab", 0);
        }

        setCurrentTab(currentTab);

        if (state != null)
            mTextFilter.setText(state.getString("filter"));
    }

    @Override
    public void onStart()
    {
        super.onStart();

        SharedPreferences settings = PlaybackService.getSettings(this);
        if (settings.getBoolean("controls_in_selector", false) != (mControls != null)) {
            finish();
            startActivity(new Intent(this, LibraryActivity.class));
        }
        mDefaultAction = Integer.parseInt(settings.getString("default_action_int", "0"));
        mLastActedId = -2;
        updateHeaders();
        mUiHandler.sendEmptyMessage(SELECT_CURRENT);
    }

    @Override
    protected void onSaveInstanceState(Bundle out)
    {
        out.putBoolean("search_box_visible", mSearchBoxVisible);
        out.putInt("current_tab", mCurrentTab);
        out.putString("filter", mTextFilter.getText().toString());
        out.putSerializable("limiter_albums", mAlbumAdapter.getLimiter());
        out.putSerializable("limiter_songs", mSongAdapter.getLimiter());
        out.putSerializable("limiter_genres", mGenreAdapter.getLimiter());
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event)
    {
        switch (keyCode) {
        case KeyEvent.KEYCODE_BACK:
            if (mSearchBoxVisible) {
                mTextFilter.setText("");
                setSearchBoxVisible(false);
            } else {
                finish();
            }
            break;
        case KeyEvent.KEYCODE_SEARCH:
            setSearchBoxVisible(!mSearchBoxVisible);
            break;
        default:
            return false;
        }

        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        if (super.onKeyDown(keyCode, event))
            return true;

        if (mTextFilter.onKeyDown(keyCode, event)) {
            if (!mSearchBoxVisible)
                setSearchBoxVisible(true);
            else
                mTextFilter.requestFocus();
            return true;
        }

        return false;
    }

    /**
     * Update the first row of the lists with the appropriate action (play all
     * or enqueue all).
     */
    private void updateHeaders()
    {
        int action = mDefaultAction;
        if (action == ACTION_LAST_USED)
            action = mLastAction;

        int res = action == ACTION_ENQUEUE ? R.string.enqueue_all : R.string.play_all;
        String text = getString(res);
        mArtistAdapter.setHeaderText(text);
        mAlbumAdapter.setHeaderText(text);
        mSongAdapter.setHeaderText(text);
    }

    /**
     * Adds songs matching the data from the given intent to the song timelime.
     *
     * @param intent An intent created with
     * {@link LibraryActivity#createClickIntent(MediaAdapter,MediaView)}.
     * @param action One of LibraryActivity.ACTION_*
     */
    private void pickSongs(Intent intent, int action)
    {
        if (action == ACTION_LAST_USED)
            action = mLastAction;

        int mode = modeForAction[action];
        QueryTask query = buildQueryFromIntent(intent, false);
        PlaybackService.get(this).addSongs(mode, query);

        mLastActedId = intent.getLongExtra("id", -1);

        if (mDefaultAction == ACTION_LAST_USED && mLastAction != action) {
            mLastAction = action;
            updateHeaders();
        }
    }

    /**
     * "Expand" the view represented by the given intent by setting the limiter
     * from the view and switching to the appropriate tab.
     *
     * @param intent An intent created with
     * {@link LibraryActivity#createClickIntent(MediaAdapter,MediaView)}.
     */
    private void expand(Intent intent)
    {
        int type = intent.getIntExtra("type", 1);
        long id = intent.getLongExtra("id", -1);
        setCurrentTab(setLimiter(mAdapters[type - 1].getLimiter(id)));
    }

    /**
     * Update the adapters with the given limiter.
     *
     * @return The tab to "expand" to
     */
    private int setLimiter(MediaAdapter.Limiter limiter)
    {
        int tab;

        if (limiter == null) {
            mAlbumAdapter.setLimiter(null);
            mSongAdapter.setLimiter(null);
            tab = -1;
        } else {
            switch (limiter.type) {
            case MediaUtils.TYPE_ALBUM:
                mSongAdapter.setLimiter(limiter);
                requestRequery(mSongAdapter);
                return 2;
            case MediaUtils.TYPE_ARTIST:
                mAlbumAdapter.setLimiter(limiter);
                mSongAdapter.setLimiter(limiter);
                tab = 1;
                break;
            case MediaUtils.TYPE_PLAYLIST:
                mSongAdapter.setLimiter(limiter);
                tab = 2;
                break;
            case MediaUtils.TYPE_GENRE:
                mSongAdapter.setLimiter(limiter);
                mAlbumAdapter.setLimiter(null);
                tab = 2;
                break;
            default:
                throw new IllegalArgumentException("Unsupported limiter type: " + limiter.type);
            }
        }

        requestRequery(mSongAdapter);
        requestRequery(mAlbumAdapter);

        return tab;
    }

    public void onItemClick(AdapterView<?> list, View view, int pos, long id)
    {
        final MediaView mediaView = (MediaView)view;
        final MediaAdapter mediaAdapter = (MediaAdapter) list.getAdapter();
        if (mediaAdapter.getMediaType() == MediaUtils.TYPE_PLAYLIST && mediaView.isHeader()) {
            Intent intent = new Intent(this, PlaylistEditor.class);
            intent.putExtra("id", (long)-1);
            startActivity(intent);
        }
        else
            if (mediaView.isExpanderPressed())
                expand(createClickIntent(mediaAdapter, mediaView));
            else if (id == mLastActedId)
                startActivity(new Intent(this, FullPlaybackActivity.class));
            else {
                pickSongs(createClickIntent((MediaAdapter)list.getAdapter(), mediaView), mDefaultAction);
            }
    }

    public void afterTextChanged(Editable editable)
    {
    }

    public void beforeTextChanged(CharSequence s, int start, int count, int after)
    {
    }

    public void onTextChanged(CharSequence text, int start, int before, int count)
    {
        String filter = text.toString();
        for (MediaAdapter adapter : mAdapters) {
            adapter.setFilter(filter);
            requestRequery(adapter);
        }
    }

    private void updateLimiterViews()
    {
        if (mLimiterViews == null)
            return;

        mLimiterViews.removeAllViews();

        MediaAdapter adapter = mCurrentAdapter;
        if (adapter == null)
            return;

        MediaAdapter.Limiter limiterData = adapter.getLimiter();
        if (limiterData == null)
            return;
        String[] limiter = limiterData.names;

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = 5;
        PaintDrawable background = new PaintDrawable(0xAA00BBBB);
        background.setCornerRadius(5);
        for (int i = 0; i != limiter.length; ++i) {
            TextView view = new TextView(this);
            view.setSingleLine();
            view.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            view.setText(limiter[i] + " | X");
            view.setTextColor(Color.WHITE);
            view.setBackgroundDrawable(background);
            view.setLayoutParams(params);
            view.setPadding(5, 2, 5, 2);
            view.setTag(i);
            view.setOnClickListener(this);
            mLimiterViews.addView(view);
        }
    }

    @Override
    public void onClick(View view)
    {
        if (view == mClearButton) {
            if (mTextFilter.getText().length() == 0)
                setSearchBoxVisible(false);
            else
                mTextFilter.setText("");
        } else if (view == mCover) {
            startActivity(new Intent(this, FullPlaybackActivity.class));
        } else if (view.getTag() != null) {
            int i = (Integer)view.getTag();
            if (view.getParent() instanceof TabWidget) {
                // a tab was clicked
                setCurrentTab(i);
            } else {
                // a limiter view was clicked
                if (i == 1) {
                    // generate the artist limiter (we need to query the artist id)
                    MediaAdapter.Limiter limiter = mSongAdapter.getLimiter();
                    Assert.assertEquals(MediaUtils.TYPE_ALBUM, limiter.type);

                    ContentResolver resolver = getContentResolver();
                    Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                    String[] projection = new String[] { MediaStore.Audio.Media.ARTIST_ID };
                    Cursor cursor = resolver.query(uri, projection, limiter.selection, null, null);
                    if (cursor != null) {
                        if (cursor.moveToNext()) {
                            setLimiter(mArtistAdapter.getLimiter(cursor.getLong(0)));
                            updateLimiterViews();
                            cursor.close();
                            return;
                        }
                        cursor.close();
                    }
                }

                setLimiter(null);
                updateLimiterViews();
            }
        } else {
            super.onClick(view);
        }
    }

    /**
     * Creates an intent based off of the media represented by the given view.
     *
     * @param adapter The adapter that owns the view.
     * @param view The MediaView to build from.
     */
    private static Intent createClickIntent(MediaAdapter adapter, MediaView view)
    {
        Intent intent = new Intent();
        intent.putExtra("type", adapter.getMediaType());
        intent.putExtra("id", view.getMediaId());
        intent.putExtra("isHeader", view.isHeader());
        intent.putExtra("title", view.getTitle());
        return intent;
    }

    /**
     * Builds a media query based off the data stored in the given intent.
     *
     * @param intent An intent created with
     * {@link LibraryActivity#createClickIntent(MediaAdapter,MediaView)}.
     * @param empty If true, use the empty projection (only query id).
     */
    private QueryTask buildQueryFromIntent(Intent intent, boolean empty)
    {
        int type = intent.getIntExtra("type", 1);

        String[] projection;
        if (type == MediaUtils.TYPE_PLAYLIST)
            projection = empty ? Song.EMPTY_PLAYLIST_PROJECTION : Song.FILLED_PLAYLIST_PROJECTION;
        else
            projection = empty ? Song.EMPTY_PROJECTION : Song.FILLED_PROJECTION;

        QueryTask query;
        if (intent.getBooleanExtra("isHeader", false)) {
            query = mAdapters[type - 1].buildSongQuery(projection);
        } else {
            long id = intent.getLongExtra("id", -1);
            query = MediaUtils.buildQuery(type, id, projection, null);
        }

        return query;
    }

    private static final int MENU_PLAY = 0;
    private static final int MENU_ENQUEUE = 1;
    private static final int MENU_EXPAND = 2;
    private static final int MENU_ADD_TO_PLAYLIST = 3;
    private static final int MENU_NEW_PLAYLIST = 4;
    private static final int MENU_EDIT = 6;
    private static final int MENU_RENAME_PLAYLIST = 7;
    private static final int MENU_SELECT_PLAYLIST = 8;
    private static final int MENU_SEND_TO = 10;

    @Override
    public void onCreateContextMenu(ContextMenu menu, View listView, ContextMenu.ContextMenuInfo absInfo)
    {
        MediaAdapter adapter = (MediaAdapter)((ListView)listView).getAdapter();
        MediaView view = (MediaView)((AdapterView.AdapterContextMenuInfo)absInfo).targetView;

        if(adapter == mPlaylistAdapter && view.isHeader())
            return;
        // Store view data in intent to avoid problems when the view data changes
        // as worked is performed in the background.
        Intent intent = createClickIntent(adapter, view);

        if (view.isHeader())
            menu.setHeaderTitle(getString(R.string.all_songs));
        else
            menu.setHeaderTitle(view.getTitle());

        menu.add(0, MENU_PLAY, 0, R.string.play).setIntent(intent);
        menu.add(0, MENU_ENQUEUE, 0, R.string.enqueue).setIntent(intent);

        if (adapter == mPlaylistAdapter) {
            menu.add(0, MENU_RENAME_PLAYLIST, 0, R.string.rename).setIntent(intent);
            menu.add(0, MENU_EDIT, 0, R.string.edit).setIntent(intent);
        }


        menu.addSubMenu(0, MENU_ADD_TO_PLAYLIST, 0, R.string.add_to_playlist).getItem().setIntent(intent);

        if (view.hasExpanders())
            menu.add(0, MENU_EXPAND, 0, R.string.expand).setIntent(intent);
        if (!view.isHeader())
            menu.addSubMenu(0, MENU_DELETE, 0, R.string.delete).getItem().setIntent(intent);
        if(adapter == mSongAdapter) {
            menu.add(0, MENU_EDIT_SONG, 0, R.string.edittag).setIntent(intent);
            menu.add(0, MENU_SEND_TO, 0, R.string.sendto).setIntent(intent);
        }
    }

    /**
     * Add a set of songs represented by the intent to a playlist. Displays a
     * Toast notifying of success.
     *
     * @param playlistId The id of the playlist to add to.
     * @param intent An intent created with
     * {@link LibraryActivity#createClickIntent(MediaAdapter,MediaView)}.
     */
    private void addToPlaylist(long playlistId, Intent intent)
    {
        QueryTask query = buildQueryFromIntent(intent, true);
        int count = Playlist.addToPlaylist(getContentResolver(), playlistId, query);

        String message = getResources().getQuantityString(R.plurals.added_to_playlist, count, count, intent.getStringExtra("playlistName"));
        mUiHandler.sendMessage(mUiHandler.obtainMessage(MSG_ADD_TO_PLAYLIST, message));
    }

    /**
     * Delete the media represented by the given intent and show a Toast
     * informing the user of this.
     *
     * @param intent An intent created with
     * {@link LibraryActivity#createClickIntent(MediaAdapter,MediaView)}.
     */
    private void delete(Intent intent)
    {
        int type = intent.getIntExtra("type", 1);
        long id = intent.getLongExtra("id", -1);

        if (type == MediaUtils.TYPE_PLAYLIST) {
            Playlist.deletePlaylist(getContentResolver(), id);
            String message = getResources().getString(R.string.playlist_deleted, intent.getStringExtra("title"));
            mUiHandler.sendMessage(mUiHandler.obtainMessage(MSG_DELETE, message));
        } else {
            int count = PlaybackService.get(this).deleteMedia(type, id);
            String message = getResources().getQuantityString(R.plurals.deleted, count, count);
            mUiHandler.sendMessage(mUiHandler.obtainMessage(MSG_DELETE, message));
        }
    }

    private void deletefromplst(Intent intent)
    {
        int type = intent.getIntExtra("type", 1);
        long id = intent.getLongExtra("id", -1);

        int count = PlaybackService.get(this).deleteMediaFromPlaylist(type, id);
        String message = getResources().getQuantityString(R.plurals.deleted, count, count);
        mUiHandler.sendMessage(mUiHandler.obtainMessage(MSG_DELETE, message));
    }

    @Override
    public boolean onContextItemSelected(MenuItem item)
    {
        Intent intent = item.getIntent();

        switch (item.getItemId()) {
        case MENU_EXPAND:
            expand(intent);
            break;
        case MENU_ENQUEUE:
            pickSongs(intent, ACTION_ENQUEUE);
            break;
        case MENU_PLAY:
            pickSongs(intent, ACTION_PLAY);
            break;
        case MENU_NEW_PLAYLIST: {
            NewPlaylistDialog dialog = new NewPlaylistDialog(this, null, R.string.create, intent);
            dialog.setDismissMessage(mHandler.obtainMessage(MSG_NEW_PLAYLIST, dialog));
            dialog.show();
            break;
        }
        case MENU_RENAME_PLAYLIST: {
            NewPlaylistDialog dialog = new NewPlaylistDialog(this, intent.getStringExtra("title"), R.string.rename, intent);
            dialog.setDismissMessage(mHandler.obtainMessage(MSG_RENAME_PLAYLIST, dialog));
            dialog.show();
            break;
        }
        case MENU_DELETE:
            SubMenu delMenu = item.getSubMenu();
            delMenu.add(0, MENU_FULL_DELETE, 0, R.string.fulldelete).setIntent(intent);
            if(intent.getIntExtra("type", 0) != MediaUtils.TYPE_PLAYLIST)
                delMenu.add(0, MENU_PLAYLIST_DELETE, 0, R.string.pllstdelete).setIntent(intent);
            break;
        case MENU_FULL_DELETE:
            mHandler.sendMessage(mHandler.obtainMessage(MSG_DELETE, intent));
            break;
        case MENU_PLAYLIST_DELETE:
            mHandler.sendMessage(mHandler.obtainMessage(MSG_DELETE_PLST, intent));
            break;
        case MENU_ADD_TO_PLAYLIST: {
            SubMenu playlistMenu = item.getSubMenu();
            playlistMenu.add(0, MENU_NEW_PLAYLIST, 0, R.string.new_playlist).setIntent(intent);
            Cursor cursor = Playlist.queryPlaylists(getContentResolver());
            if (cursor != null) {
                for (int i = 0, count = cursor.getCount(); i != count; ++i) {
                    cursor.moveToPosition(i);
                    long id = cursor.getLong(0);
                    String name = cursor.getString(1);
                    Intent copy = new Intent(intent);
                    copy.putExtra("playlist", id);
                    copy.putExtra("playlistName", name);
                    playlistMenu.add(0, MENU_SELECT_PLAYLIST, 0, name).setIntent(copy);
                }
                cursor.close();
            }
            break;
        }
        case MENU_EDIT: {
            Intent launch = new Intent(this, PlaylistEditor.class);
            launch.putExtra("id", intent.getLongExtra("id", 0));
            startActivity(launch);
            break;
        }
        case MENU_SELECT_PLAYLIST:
            mHandler.sendMessage(mHandler.obtainMessage(MSG_ADD_TO_PLAYLIST, intent));
            break;
        case MENU_EDIT_SONG: {
            int type = intent.getIntExtra("type", 1);
            long id = intent.getLongExtra("id", -1);
            if (type == MediaUtils.TYPE_SONG)
            {
                Intent tag = new Intent(this, TagEditActivity.class);
                tag.putExtra("SongId", id);
                startActivity(tag);
            }
            break;
        }
        case MENU_SEND_TO: {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            ContentResolver resolver = getContentResolver();
            String[] projection = new String [] { MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA };
            Cursor cursor = MediaUtils.buildQuery(MediaUtils.TYPE_SONG, intent.getLongExtra("id", -1), projection, null).runQuery(resolver);

            if (cursor != null)
                while (cursor.moveToNext()) {
                    File SongFile = new File(cursor.getString(1));
                    i.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(SongFile));

                    try {
                        startActivity(Intent.createChooser(i, getResources().getString(R.string.sendto)));
                    } catch (android.content.ActivityNotFoundException ex) {
                        Toast.makeText(LibraryActivity.this, R.string.send_no_apps, Toast.LENGTH_SHORT).show();
                    }
                }
            break;
        }
        }


        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        menu.add(0, MENU_PLAYBACK, 0, R.string.playback_view).setIcon(R.drawable.ic_menu_gallery);
        menu.add(0, MENU_SEARCH, 0, R.string.search).setIcon(R.drawable.ic_menu_search);
        menu.addSubMenu(0, MENU_MORE, 0, R.string.more).setIcon(android.R.drawable.ic_menu_more);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        switch (item.getItemId()) {
        case MENU_MORE:
            Menu moreMenu = item.getSubMenu();
            moreMenu.clear();
            moreMenu.add(0, MENU_RELOAD_SONGS, 0, R.string.reload_songs);
            moreMenu.add(0, MENU_PICK_FOLDER, 0, R.string.pick_folder);
            return true;
        case MENU_SEARCH:
            setSearchBoxVisible(!mSearchBoxVisible);
            return true;
        case MENU_PLAYBACK:
            startActivity(new Intent(this, FullPlaybackActivity.class));
            return true;
        case MENU_RELOAD_SONGS:
            getApplicationContext().sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.parse("file://" + Environment.getExternalStorageDirectory())));
            return true;
        case MENU_PICK_FOLDER:
            startActivity(new Intent(this, FolderSelectActivity.class));
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Hook up a ListView to this Activity and the supplied adapter
     *
     * @param id The id of the ListView
     * @param type The media type for the adapter.
     * @param expandable True if the rows are expandable.
     * @param hasHeader True if the view should have a header row.
     * @param limiter The initial limiter to set on the adapter.
     */
    private MediaAdapter setupView(int id, int type, boolean expandable, boolean hasHeader, MediaAdapter.Limiter limiter)
    {
        final ListView view = (ListView) findViewById(id);
        MediaAdapter adapter = new MediaAdapter(this, type, expandable, hasHeader, limiter);
        view.setAdapter(adapter);

        view.setOnItemClickListener(this);
        view.setOnCreateContextMenuListener(this);
        view.setCacheColorHint(Color.BLACK);
        view.setDivider(null);

        return adapter;
    }

    /**
     * Call addToPlaylist with the results from a NewPlaylistDialog stored in
     * obj.
     */
    private static final int MSG_NEW_PLAYLIST = 11;
    /**
     * Delete the songs represented by the intent stored in obj.
     */
    private static final int MSG_DELETE = 12;
    /**
     * Call renamePlaylist with the results from a NewPlaylistDialog stored in
     * obj.
     */
    private static final int MSG_RENAME_PLAYLIST = 13;
    /**
     * Call addToPlaylist with data from the intent in obj.
     */
    private static final int MSG_ADD_TO_PLAYLIST = 14;
    /**
     * Call requery on all adapters.
     */
    private static final int CONTENT_CHANGED = 30;

    private static final int SELECT_CURRENT = 31;

    private static final int MSG_DELETE_PLST = 22;
    /**
     * A Handler running on the UI thread, in contrast with mHandler which runs
     * on a worker thread.
     */
    protected Handler mUiHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {

            switch (msg.what) {
            case CONTENT_CHANGED:
                if(msg.obj == null)
                    for (MediaAdapter adapter : mAdapters)
                        requestRequery(adapter);
                else
                    requestRequery((MediaAdapter)msg.obj);
                break;
            case SELECT_CURRENT:
                long id = MediaUtils.getIdforMediaType(LibraryActivity.this, mCurrentTab + 1);

                if(id == -1)
                    return;

                final ListView view = (ListView) mLists.getChildAt(mCurrentTab);
                for(int k = 0; k < view.getCount(); k++)
                    if(view.getItemIdAtPosition(k) == id)
                        if(java.lang.Math.abs((view.getFirstVisiblePosition() + view.getLastVisiblePosition()) / 2 - k) < 30)
                            view.smoothScrollToPosition(k);
                        else
                            view.setSelection(k);

                mCurrentAdapter.markChecked();
                break;
            case MSG_DELETE:
            case MSG_ADD_TO_PLAYLIST:
                Toast.makeText(LibraryActivity.this, (String)msg.obj, Toast.LENGTH_SHORT).show();
                break;
                default:
                    break;
            }
        }

    };


    @Override
    public boolean handleMessage(Message message)
    {
        switch (message.what) {
        case MSG_ADD_TO_PLAYLIST: {
            Intent intent = (Intent)message.obj;
            addToPlaylist(intent.getLongExtra("playlist", -1), intent);
            break;
        }
        case MSG_NEW_PLAYLIST: {
            NewPlaylistDialog dialog = (NewPlaylistDialog)message.obj;
            if (dialog.isAccepted()) {
                String name = dialog.getText();
                long playlistId = Playlist.createPlaylist(getContentResolver(), name);
                Intent intent = dialog.getIntent();
                intent.putExtra("playlistName", name);
                addToPlaylist(playlistId, intent);
            }
            break;
        }
        case MSG_DELETE:
            delete((Intent)message.obj);
            break;
        case MSG_DELETE_PLST:
            deletefromplst((Intent)message.obj);
            break;
        case MSG_RENAME_PLAYLIST: {
            NewPlaylistDialog dialog = (NewPlaylistDialog)message.obj;
            if (dialog.isAccepted()) {
                long playlistId = dialog.getIntent().getLongExtra("id", -1);
                Playlist.renamePlaylist(getContentResolver(), playlistId, dialog.getText());
            }
            break;
        }
        default:
            return super.handleMessage(message);
        }

        return true;
    }

    /**
     * Requery the given adapter. If it is the current adapter, requery
     * immediately. Otherwise, mark the adapter as needing a requery and requery
     * when its tab is selected.
     */
    public void requestRequery(MediaAdapter adapter)
    {
        if (adapter == mCurrentAdapter) {
            runQuery(adapter);
        } else {
            adapter.requestRequery();
            // Clear the data for non-visible adapters (so we don't show the old
            // data briefly when we later switch to that adapter)
            //adapter.changeCursor(null);
        }
    }

    /**
     * Schedule a query to be run for the given adapter on the worker thread.
     *
     * @param adapter The adapter to run the query for.
     */
    private void runQuery(MediaAdapter adapter)
    {
        QueryTask query = adapter.buildQuery();
        final Cursor cursor = query.runQuery(getContentResolver());
        adapter.changeCursor(cursor);
    }

    @Override
    public void onMediaChange()
    {
        mUiHandler.sendEmptyMessage(CONTENT_CHANGED);
    }

    private void setSearchBoxVisible(boolean visible)
    {
        mSearchBoxVisible = visible;
        mSearchBox.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (mControls != null)
            mControls.setVisibility(visible ? View.GONE : View.VISIBLE);
        if (visible)
            mSearchBox.requestFocus();
    }

    @Override
    protected void onSongChange(final Song song)
    {
        super.onSongChange(song);

        if (mStatusText != null) {
            Uri cover = null;

            if (song == null) {
                mStatusText.setText(R.string.none);
            } else {
                Resources res = getResources();
                String title = song.title == null ? res.getString(R.string.unknown) : song.title;
                String artist = song.artist == null ? res.getString(R.string.unknown) : song.artist;
                mStatusText.setText(res.getString(R.string.title_by_artist, title, artist));
                cover = song.hasCover(this) ? song.getCoverUri() : null;
            }

            if (Song.mDisableCoverArt)
                mCover.setVisibility(View.GONE);
            else if (cover == null)
                mCover.setImageResource(R.drawable.albumart_mp_unknown_list);
            else
                mCover.setImageURI(cover);
        }
    }

    /**
     * Switch to the tab at the given index.
     */
    private void setCurrentTab(int i)
    {
        MediaAdapter adapter = mAdapters[i];
        mCurrentAdapter = adapter;
        if (adapter.isRequeryNeeded())
            runQuery(adapter);
        mTabWidget.setCurrentTab(i);
        mLists.getChildAt(mCurrentTab).setVisibility(View.GONE);
        mLists.getChildAt(i).setVisibility(View.VISIBLE);
        mCurrentTab = i;
        updateLimiterViews();

        mUiHandler.sendEmptyMessage(SELECT_CURRENT);
    }

    @Override
    protected void setSong(Song song) {
        super.setSong(song);
        mUiHandler.sendEmptyMessage(SELECT_CURRENT);
    }
}
