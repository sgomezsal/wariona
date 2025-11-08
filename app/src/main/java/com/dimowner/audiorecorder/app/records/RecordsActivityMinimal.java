/*
 * Copyright 2024
 * Minimalist Audio Recorder - Simplified Records Activity
 */

package com.dimowner.audiorecorder.app.records;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dimowner.audiorecorder.ARApplication;
import com.dimowner.audiorecorder.ColorMap;
import com.dimowner.audiorecorder.R;
import com.dimowner.audiorecorder.app.PlaybackService;
import com.dimowner.audiorecorder.app.main.MainActivityMinimal;
import com.dimowner.audiorecorder.data.database.Record;
import com.dimowner.audiorecorder.util.AndroidUtils;
import com.dimowner.audiorecorder.util.FileUtil;
import com.dimowner.audiorecorder.util.TimeUtils;

import java.util.ArrayList;
import java.util.List;

public class RecordsActivityMinimal extends Activity implements RecordsContract.View {

	public static final int REQ_CODE_READ_EXTERNAL_STORAGE_PLAYBACK = 406;

	private RecyclerView recyclerView;
	private LinearLayoutManager layoutManager;
	private RecordsAdapterMinimal adapter;
	private TextView txtEmpty;
	private ImageButton btnBack;

	private RecordsContract.UserActionsListener presenter;
	private ColorMap colorMap;
	private Record currentPlayingRecord = null;
	private boolean isPlaying = false;

	public static Intent getStartIntent(Context context) {
		Intent intent = new Intent(context, RecordsActivityMinimal.class);
		intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
		return intent;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		colorMap = ARApplication.getInjector().provideColorMap(getApplicationContext());
		setTheme(colorMap.getAppThemeResource());
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_records_minimal);

		// Set white background
		getWindow().getDecorView().setBackgroundColor(getResources().getColor(android.R.color.white));

		btnBack = findViewById(R.id.btn_back);
		txtEmpty = findViewById(R.id.txtEmpty);

		btnBack.setOnClickListener(view -> {
			finish();
			ARApplication.getInjector().releaseRecordsPresenter();
		});

		recyclerView = findViewById(R.id.recycler_view);
		recyclerView.setHasFixedSize(true);
		layoutManager = new LinearLayoutManager(getApplicationContext());
		recyclerView.setLayoutManager(layoutManager);

		adapter = new RecordsAdapterMinimal();
		adapter.setOnItemClickListener((record, position) -> {
			// Play or pause the record
			if (currentPlayingRecord != null && currentPlayingRecord.getId() == record.getId() && isPlaying) {
				presenter.pausePlayback();
			} else {
				currentPlayingRecord = record;
				presenter.setActiveRecord(record.getId(), new RecordsContract.Callback() {
					@Override
					public void onSuccess() {
						String path = record.getPath();
						if (FileUtil.isFileInExternalStorage(getApplicationContext(), path)) {
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
								showError("File not available");
								return;
							} else if (checkStoragePermissionPlayback()) {
								presenter.startPlayback();
							}
						} else {
							presenter.startPlayback();
						}
					}

					@Override
					public void onError(Exception e) {
						showError("Failed to load record");
					}
				});
			}
		});
		adapter.setOnDeleteClickListener((record, position) -> {
			AndroidUtils.showDialogYesNo(
					RecordsActivityMinimal.this,
					R.drawable.ic_delete_forever_dark,
					getString(R.string.warning),
					getString(R.string.delete_record, record.getName()),
					v -> presenter.deleteRecord(record.getId(), record.getPath())
			);
		});

		recyclerView.setAdapter(adapter);

		presenter = ARApplication.getInjector().provideRecordsPresenter(getApplicationContext());
	}

	@Override
	protected void onStart() {
		super.onStart();
		presenter.bindView(this);
		presenter.loadRecords();
	}

	@Override
	protected void onStop() {
		super.onStop();
		if (presenter != null) {
			presenter.unbindView();
		}
	}

	@Override
	public void onBackPressed() {
		super.onBackPressed();
		ARApplication.getInjector().releaseRecordsPresenter();
	}

	private boolean checkStoragePermissionPlayback() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
				requestPermissions(
						new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
						REQ_CODE_READ_EXTERNAL_STORAGE_PLAYBACK);
				return false;
			}
		}
		return true;
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		if (requestCode == REQ_CODE_READ_EXTERNAL_STORAGE_PLAYBACK && grantResults.length > 0
				&& grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			if (currentPlayingRecord != null) {
				presenter.startPlayback();
			}
		}
	}

	// RecordsContract.View implementation
	@Override
	public void showRecords(List<com.dimowner.audiorecorder.app.records.ListItem> records, int order) {
		if (records.size() == 0) {
			txtEmpty.setVisibility(View.VISIBLE);
			adapter.setData(new ArrayList<>());
		} else {
			adapter.setData(records);
			txtEmpty.setVisibility(View.GONE);
		}
	}

	@Override
	public void addRecords(List<com.dimowner.audiorecorder.app.records.ListItem> records, int order) {
		adapter.addData(records);
		txtEmpty.setVisibility(View.GONE);
	}

	@Override
	public void showEmptyList() {
		txtEmpty.setText(R.string.no_records);
		txtEmpty.setVisibility(View.VISIBLE);
	}

	@Override
	public void showEmptyBookmarksList() {
		showEmptyList();
	}

	@Override
	public void showPanelProgress() {
		// Not used
	}

	@Override
	public void hidePanelProgress() {
		// Not used
	}

	@Override
	public void decodeRecord(int id) {
		// Not used
	}

	@Override
	public void showRecordName(String name) {
		// Not used
	}

	@Override
	public void showRename(Record record) {
		// Not used
	}

	@Override
	public void onDeleteRecord(long id) {
		adapter.removeRecord(id);
		if (currentPlayingRecord != null && currentPlayingRecord.getId() == id) {
			presenter.stopPlayback();
			currentPlayingRecord = null;
			isPlaying = false;
		}
		if (adapter.getItemCount() == 0) {
			showEmptyList();
		}
	}

	@Override
	public void hidePlayPanel() {
		// Not used
	}

	@Override
	public void addedToBookmarks(int id, boolean isActive) {
		// Not used
	}

	@Override
	public void removedFromBookmarks(int id, boolean isActive) {
		// Not used
	}

	@Override
	public void showSortType(int type) {
		// Not used
	}

	@Override
	public void showActiveRecord(int id) {
		adapter.setActiveRecord(id);
	}

	@Override
	public void bookmarksSelected() {
		// Not used
	}

	@Override
	public void bookmarksUnselected() {
		// Not used
	}

	@Override
	public void showRecordInfo(com.dimowner.audiorecorder.app.info.RecordInfo info) {
		// Not used
	}

	@Override
	public void showRecordsLostMessage(List<Record> list) {
		// Not used
	}

	@Override
	public void cancelMultiSelect() {
		// Not used
	}

	@Override
	public void showProgress() {
		// Not used
	}

	@Override
	public void hideProgress() {
		// Not used
	}

	@Override
	public void showError(String message) {
		Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
	}

	@Override
	public void showError(int resId) {
		Toast.makeText(getApplicationContext(), resId, Toast.LENGTH_LONG).show();
	}

	@Override
	public void showMessage(int resId) {
		Toast.makeText(getApplicationContext(), resId, Toast.LENGTH_LONG).show();
	}

	@Override
	public void showPlayerPanel() {
		// Don't show player panel in minimalist version
		// Just start playback directly
	}

	@Override
	public void startPlaybackService() {
		if (currentPlayingRecord != null) {
			PlaybackService.startServiceForeground(getApplicationContext(), currentPlayingRecord.getName());
		}
	}

	@Override
	public void showPlayStart() {
		isPlaying = true;
		adapter.setPlayingState(currentPlayingRecord != null ? currentPlayingRecord.getId() : -1, true);
	}

	@Override
	public void showPlayPause() {
		isPlaying = false;
		adapter.setPlayingState(currentPlayingRecord != null ? currentPlayingRecord.getId() : -1, false);
	}

	@Override
	public void showPlayStop() {
		isPlaying = false;
		currentPlayingRecord = null;
		adapter.setPlayingState(-1, false);
	}

	@Override
	public void showNextRecord() {
		// Not used
	}

	@Override
	public void showPrevRecord() {
		// Not used
	}

	@Override
	public void showTrashBtn() {
		// Not used
	}

	@Override
	public void hideTrashBtn() {
		// Not used
	}

	@Override
	public void showWaveForm(int[] waveForm, long duration, long playbackMills) {
		// Not used
	}

	@Override
	public void showDuration(String duration) {
		// Not used
	}

	@Override
	public void onPlayProgress(long mills, int percent) {
		// Not used
	}
}

