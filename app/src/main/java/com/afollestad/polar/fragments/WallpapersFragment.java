package com.afollestad.polar.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.view.ViewCompat;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SearchView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.assent.Assent;
import com.afollestad.assent.AssentCallback;
import com.afollestad.assent.PermissionResultSet;
import com.afollestad.bridge.Bridge;
import com.afollestad.bridge.BridgeException;
import com.afollestad.bridge.Callback;
import com.afollestad.bridge.Request;
import com.afollestad.bridge.Response;
import com.afollestad.materialdialogs.MaterialDialog;
import com.afollestad.polar.R;
import com.afollestad.polar.adapters.WallpaperAdapter;
import com.afollestad.polar.fragments.base.BaseTabFragment;
import com.afollestad.polar.util.Utils;
import com.afollestad.polar.util.WallpaperUtils;
import com.afollestad.polar.viewer.ViewerActivity;

import java.io.File;
import java.util.Locale;

import butterknife.Bind;
import butterknife.ButterKnife;

import static com.afollestad.polar.viewer.ViewerActivity.STATE_CURRENT_POSITION;

/**
 * @author Aidan Follestad (afollestad)
 */
public class WallpapersFragment extends BaseTabFragment implements
        SearchView.OnQueryTextListener, SearchView.OnCloseListener {

    @Bind(android.R.id.list)
    RecyclerView mRecyclerView;
    @Bind(android.R.id.empty)
    TextView mEmpty;
    @Bind(android.R.id.progress)
    View mProgress;

    public static final int RQ_CROPANDSETWALLPAPER = 8585;
    public static final int RQ_VIEWWALLPAPER = 2001;

    private WallpaperAdapter mAdapter;
    private WallpaperUtils.WallpapersHolder mWallpapers;
    private String mQueryText;
    private static Toast mToast;

    public static void showToast(Context context, @StringRes int message) {
        showToast(context, context.getString(message));
    }

    public static void showToast(Context context, String message) {
        if (mToast != null)
            mToast.cancel();
        mToast = Toast.makeText(context, message, Toast.LENGTH_LONG);
        mToast.show();
    }

    public WallpapersFragment() {
    }

    @Override
    public int getTitle() {
        return R.string.wallpapers;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable("wallpapers", mWallpapers);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_recyclerview, container, false);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.wallpapers, menu);
        super.onCreateOptionsMenu(menu, inflater);
        MenuItem mSearchItem = menu.findItem(R.id.search);
        SearchView mSearchView = (SearchView) MenuItemCompat.getActionView(mSearchItem);
        mSearchView.setQueryHint(getString(R.string.search_wallpapers));
        mSearchView.setOnQueryTextListener(this);
        mSearchView.setOnCloseListener(this);
        mSearchView.setImeOptions(EditorInfo.IME_ACTION_DONE);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.reload) {
            mWallpapers = null;
            load(false);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openViewer(View view, int index) {
        ImageView iv = (ImageView) view.findViewById(R.id.image);

        final Intent intent = new Intent(getActivity(), ViewerActivity.class);
        Bundle extras = new Bundle();
        extras.putSerializable("wallpapers", mWallpapers);
        extras.putInt(STATE_CURRENT_POSITION, index);
        intent.putExtras(extras);

        final String transName = "view_" + index;
        ViewCompat.setTransitionName(iv, transName);
        final ActivityOptionsCompat options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                getActivity(), iv, transName);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            //Somehow this works (setting status bar color in both MainActivity and here)
            //to avoid image glitching through on when ViewActivity is first created.
            getActivity().getWindow().setStatusBarColor(ContextCompat.getColor(getActivity(), R.color.primary_2_light));
            View statusBar = getActivity().getWindow().getDecorView().findViewById(android.R.id.statusBarBackground);
            if (statusBar != null) {
                statusBar.post(new Runnable() {
                    @Override
                    public void run() {
                        ActivityCompat.startActivityForResult(getActivity(), intent, RQ_VIEWWALLPAPER, options.toBundle());
                    }
                });
                return;
            }
        }

        ActivityCompat.startActivityForResult(getActivity(), intent, RQ_VIEWWALLPAPER, options.toBundle());
    }

    private static Activity mContextCache;
    private static View mViewCache;
    private static int mImageIndexCache;
    private static WallpaperUtils.Wallpaper mWallpaperCache;
    private static File mFileCache;

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void resetOptionCache(boolean delete) {
        mContextCache = null;
        mViewCache = null;
        mImageIndexCache = 0;
        mWallpaperCache = null;
        if (delete && mFileCache != null) {
            mFileCache.delete();
            final File[] contents = mFileCache.getParentFile().listFiles();
            if (contents != null && contents.length > 0)
                mFileCache.getParentFile().delete();
        }
    }

    public static void performOptionCached() {
        if (mContextCache != null)
            performOption(mContextCache, mViewCache, mImageIndexCache, mWallpaperCache);
    }

    public static void performOption(final Activity context, final View view, final int imageIndex, final WallpaperUtils.Wallpaper wallpaper) {
        mContextCache = context;
        mViewCache = view;
        mImageIndexCache = imageIndex;
        mWallpaperCache = wallpaper;

        if (!Assent.isPermissionGranted(Assent.WRITE_EXTERNAL_STORAGE)) {
            Assent.requestPermissions(new AssentCallback() {
                @Override
                public void onPermissionResult(PermissionResultSet permissionResultSet) {
                    if (permissionResultSet.isGranted(Assent.WRITE_EXTERNAL_STORAGE))
                        performOptionCached();
                    else
                        Toast.makeText(mContextCache, R.string.write_storage_permission_denied, Toast.LENGTH_LONG).show();
                }
            }, 69, Assent.WRITE_EXTERNAL_STORAGE);
            return;
        }

        final File saveFolder = new File(Environment.getExternalStorageDirectory(), context.getString(R.string.app_name));
        //noinspection ResultOfMethodCallIgnored
        saveFolder.mkdirs();

        final String name;
        final String extension = wallpaper.url.toLowerCase(Locale.getDefault()).endsWith(".png") ? ".png" : ".jpeg";
        if (imageIndex == 0) {
            // Crop/Apply
            name = String.format("%s_%s_wallpaper.%s",
                    wallpaper.name.replace(" ", "_"),
                    wallpaper.author.replace(" ", "_"),
                    extension);
        } else {
            // Save
            name = String.format("%s_%s.%s",
                    wallpaper.name.replace(" ", "_"),
                    wallpaper.author.replace(" ", "_"),
                    extension);
        }

        mFileCache = new File(saveFolder, name);

        if (!mFileCache.exists()) {
            final MaterialDialog dialog = new MaterialDialog.Builder(context)
                    .content(R.string.downloading_wallpaper)
                    .progress(true, -1)
                    .cancelable(false)
                    .show();
            Bridge.get(wallpaper.url)
                    .request(new Callback() {
                        @Override
                        public void response(Request request, Response response, BridgeException e) {
                            if (e != null) {
                                dialog.dismiss();
                                if (e.reason() == BridgeException.REASON_REQUEST_CANCELLED) return;
                                Utils.showError(context, e);
                            } else {
                                try {
                                    response.asFile(mFileCache);
                                    finishOption(mContextCache, mImageIndexCache, dialog);
                                } catch (BridgeException e1) {
                                    dialog.dismiss();
                                    Utils.showError(context, e1);
                                }
                            }
                        }
                    });
        } else {
            finishOption(context, imageIndex, null);
        }
    }

    public static void finishOption(final Activity context, int imageIndex, @Nullable final MaterialDialog dialog) {
        MediaScannerConnection.scanFile(context,
                new String[]{mFileCache.getAbsolutePath()}, null,
                new MediaScannerConnection.OnScanCompletedListener() {
                    public void onScanCompleted(String path, Uri uri) {
                        Log.i("WallpaperScan", "Scanned " + path + ":");
                        Log.i("WallpaperScan", "-> uri = " + uri);
                    }
                });

        if (imageIndex == 0) {
            // Apply
            if (dialog != null)
                dialog.dismiss();
            final Intent intent = new Intent(Intent.ACTION_ATTACH_DATA)
                    .setDataAndType(Uri.fromFile(mFileCache), "image/*")
                    .putExtra("mimeType", "image/*");
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.set_wallpaper_using)));
        } else {
            // Save
            if (dialog != null)
                dialog.dismiss();
            showToast(context, context.getString(R.string.saved_to_x, mFileCache.getAbsolutePath()));
            resetOptionCache(false);
        }
    }

    private void showOptions(final int imageIndex) {
        new MaterialDialog.Builder(getActivity())
                .title(R.string.wallpaper)
                .items(R.array.wallpaper_options)
                .itemsCallback(new MaterialDialog.ListCallback() {
                    @Override
                    public void onSelection(MaterialDialog materialDialog, View view, final int i, CharSequence charSequence) {
                        final WallpaperUtils.Wallpaper wallpaper = mWallpapers.get(imageIndex);
                        performOption(getActivity(), getView(), imageIndex, wallpaper);
                    }
                }).show();
    }

    private void setListShown(boolean shown) {
        final View v = getView();
        if (v != null) {
            mRecyclerView.setVisibility(shown ?
                    View.VISIBLE : View.GONE);
            mProgress.setVisibility(shown ?
                    View.GONE : View.VISIBLE);
            mEmpty.setVisibility(shown && mAdapter.getItemCount() == 0 ?
                    View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ButterKnife.bind(this, view);

        mRecyclerView.setClipToPadding(false);
        mAdapter = new WallpaperAdapter(new WallpaperAdapter.ClickListener() {
            @Override
            public boolean onClick(View view, int index, boolean longPress) {
                if (longPress) {
                    showOptions(index);
                    return true;
                } else {
                    openViewer(view, index);
                    return false;
                }
            }
        });
        mRecyclerView.setLayoutManager(new GridLayoutManager(getActivity(), getResources().getInteger(R.integer.wallpaper_grid_width)));
        mRecyclerView.setAdapter(mAdapter);

        if (savedInstanceState != null)
            mWallpapers = (WallpaperUtils.WallpapersHolder) savedInstanceState.getSerializable("wallpapers");
        if (getActivity() != null) load();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        ButterKnife.unbind(this);
        mContextCache = null;
        mViewCache = null;
        mImageIndexCache = 0;
        mWallpaperCache = null;
    }

    public void load() {
        load(!WallpaperUtils.didExpire(getActivity()));
    }

    private void load(boolean allowCached) {
        if (allowCached && mWallpapers != null) {
            mAdapter.set(mWallpapers);
            setListShown(true);
            return;
        }
        setListShown(false);
        WallpaperUtils.getAll(getActivity(), allowCached, new WallpaperUtils.WallpapersCallback() {
            @Override
            public void onRetrievedWallpapers(WallpaperUtils.WallpapersHolder wallpapers, Exception error) {
                if (error != null) {
                    mEmpty.setText(error.getMessage());
                } else {
                    mEmpty.setText(R.string.no_wallpapers);
                    mWallpapers = wallpapers;
                    mAdapter.set(mWallpapers);
                }
                setListShown(true);
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        Bridge.cancelAll()
                .tag(WallpapersFragment.class.getName())
                .commit();
    }

    // Search

    private final Runnable searchRunnable = new Runnable() {
        @Override
        public void run() {
            mAdapter.filter(mQueryText);
            setListShown(true);
        }
    };

    @Override
    public boolean onQueryTextSubmit(String query) {
        return false;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        mQueryText = newText;
        mRecyclerView.postDelayed(searchRunnable, 400);
        return false;
    }

    @Override
    public boolean onClose() {
        mRecyclerView.removeCallbacks(searchRunnable);
        mQueryText = null;
        mAdapter.filter(null);
        setListShown(true);
        return false;
    }
}