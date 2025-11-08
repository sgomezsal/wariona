/*
 * Copyright 2024
 * Minimalist Audio Recorder - Simplified Records Adapter
 */

package com.dimowner.audiorecorder.app.records;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.dimowner.audiorecorder.R;
import com.dimowner.audiorecorder.data.database.Record;
import com.dimowner.audiorecorder.util.TimeUtils;

import java.util.ArrayList;
import java.util.List;

public class RecordsAdapterMinimal extends RecyclerView.Adapter<RecordsAdapterMinimal.ViewHolder> {

	public interface OnItemClickListener {
		void onItemClick(Record record, int position);
	}

	public interface OnDeleteClickListener {
		void onDeleteClick(Record record, int position);
	}

	private List<ListItem> records = new ArrayList<>();
	private OnItemClickListener onItemClickListener;
	private OnDeleteClickListener onDeleteClickListener;
	private int activeRecordId = -1;
	private boolean isPlaying = false;

	public void setOnItemClickListener(OnItemClickListener listener) {
		this.onItemClickListener = listener;
	}

	public void setOnDeleteClickListener(OnDeleteClickListener listener) {
		this.onDeleteClickListener = listener;
	}

	public void setData(List<ListItem> records) {
		if (records != null) {
			// Filter to only include normal record items
			this.records = new ArrayList<>();
			for (ListItem item : records) {
				if (item.getType() == ListItem.ITEM_TYPE_NORMAL) {
					this.records.add(item);
				}
			}
		} else {
			this.records = new ArrayList<>();
		}
		notifyDataSetChanged();
	}

	public void addData(List<ListItem> records) {
		if (records != null) {
			int startPos = this.records.size();
			// Filter to only include normal record items
			for (ListItem item : records) {
				if (item.getType() == ListItem.ITEM_TYPE_NORMAL) {
					this.records.add(item);
				}
			}
			int addedCount = this.records.size() - startPos;
			if (addedCount > 0) {
				notifyItemRangeInserted(startPos, addedCount);
			}
		}
	}

	public void removeRecord(long id) {
		for (int i = 0; i < records.size(); i++) {
			ListItem item = records.get(i);
			if (item.getType() == ListItem.ITEM_TYPE_NORMAL && item.getId() == id) {
				records.remove(i);
				notifyItemRemoved(i);
				break;
			}
		}
	}

	public void setActiveRecord(int id) {
		int oldActive = activeRecordId;
		activeRecordId = id;
		if (oldActive != -1) {
			notifyItemChanged(findPositionById(oldActive));
		}
		if (activeRecordId != -1) {
			notifyItemChanged(findPositionById(activeRecordId));
		}
	}

	public void setPlayingState(int recordId, boolean playing) {
		isPlaying = playing;
		activeRecordId = recordId;
		notifyDataSetChanged();
	}

	private int findPositionById(int id) {
		for (int i = 0; i < records.size(); i++) {
			ListItem item = records.get(i);
			if (item.getType() == ListItem.ITEM_TYPE_NORMAL && item.getId() == id) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public int getItemCount() {
		return records.size();
	}
	
	@Override
	public int getItemViewType(int position) {
		return records.get(position).getType();
	}

	@NonNull
	@Override
	public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_record_minimal, parent, false);
		return new ViewHolder(view);
	}

	@Override
	public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
		ListItem item = records.get(position);
		
		// Skip non-record items (headers, footers, dates)
		if (item.getType() != ListItem.ITEM_TYPE_NORMAL) {
			holder.itemView.setVisibility(View.GONE);
			return;
		}
		holder.itemView.setVisibility(View.VISIBLE);
		
		Record record = convertToRecord(item);

		holder.txtName.setText(record.getName());
		holder.txtDuration.setText(TimeUtils.formatTimeIntervalHourMinSec2(record.getDuration() / 1000));

		// Update play button icon
		if (record.getId() == activeRecordId && isPlaying) {
			holder.btnPlay.setImageResource(R.drawable.ic_pause);
		} else {
			holder.btnPlay.setImageResource(R.drawable.ic_play);
		}

		// Click listeners
		holder.itemView.setOnClickListener(v -> {
			if (onItemClickListener != null) {
				onItemClickListener.onItemClick(record, position);
			}
		});

		holder.btnPlay.setOnClickListener(v -> {
			if (onItemClickListener != null) {
				onItemClickListener.onItemClick(record, position);
			}
		});

		holder.btnDelete.setOnClickListener(v -> {
			if (onDeleteClickListener != null) {
				onDeleteClickListener.onDeleteClick(record, position);
			}
		});
	}

	private Record convertToRecord(ListItem item) {
		// Convert ListItem to Record
		// This is a simplified conversion - you may need to adjust based on your Record class
		return new Record(
				(int)item.getId(),
				item.getName(),
				item.getDuration(),
				item.getCreated(),
				item.getAdded(),
				Long.MAX_VALUE,
				item.getPath(),
				item.getFormat(),
				item.getSize(),
				item.getSampleRate(),
				item.getChannelCount(),
				item.getBitrate(),
				false,
				false,
				item.getAmps()
		);
	}

	static class ViewHolder extends RecyclerView.ViewHolder {
		TextView txtName;
		TextView txtDuration;
		ImageButton btnPlay;
		ImageButton btnDelete;

		ViewHolder(View itemView) {
			super(itemView);
			txtName = itemView.findViewById(R.id.txt_name);
			txtDuration = itemView.findViewById(R.id.txt_duration);
			btnPlay = itemView.findViewById(R.id.btn_play);
			btnDelete = itemView.findViewById(R.id.btn_delete);
		}
	}
}

