package com.firebase.ui.firestore;

import android.util.Log;

import com.firebase.ui.common.BaseObservableSnapshotArray;
import com.firebase.ui.common.ChangeEventType;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.List;


/**
 * Exposes a Firestore query as an observable list of objects.
 */
public class FirestoreArray<T>
        extends BaseObservableSnapshotArray<DocumentSnapshot, ChangeEventListener, T>
        implements EventListener<QuerySnapshot>  {

    private static final String TAG = "FirestoreArray";

    private Query mQuery;
    private ListenerRegistration mRegistration;

    private List<DocumentSnapshot> mSnapshots;
    private CachingSnapshotParser<T> mCache;

    public FirestoreArray(Query query, final Class<T> modelClass) {
        this(query, new SnapshotParser<T>() {
            @Override
            public T parseSnapshot(DocumentSnapshot snapshot) {
                return snapshot.toObject(modelClass);
            }
        });
    }

    public FirestoreArray(Query query, SnapshotParser<T> parser) {
        super();

        mQuery = query;
        mSnapshots = new ArrayList<>();
        mCache = new CachingSnapshotParser<>(parser);

        setSnapshotParser(mCache);
    }

    @Override
    protected void onCreate() {
        super.onCreate();

        startListening();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        stopListening();
        mCache.clearData();
    }

    @Override
    protected void onListenerAdded(ChangeEventListener listener) {
        super.onListenerAdded(listener);

        for (int i = 0; i < size(); i++) {
            listener.onChildChanged(ChangeEventType.ADDED, get(i), i, -1);
        }

        // TODO(samstern): track hasDataChanged()
        if (hasDataChanged()) {
            listener.onDataChanged();
        }
    }

    @Override
    public void onEvent(QuerySnapshot snapshots, FirebaseFirestoreException e) {
        if (e != null) {
            Log.w(TAG, "Error in snapshot listener", e);
            notifyOnError(e);
            return;
        }

        // Break down each document event
        List<DocumentChange> changes = snapshots.getDocumentChanges();
        for (DocumentChange change : changes) {
            switch (change.getType()) {
                case ADDED:
                    onDocumentAdded(change);
                    break;
                case REMOVED:
                    onDocumentRemoved(change);
                    break;
                case MODIFIED:
                    onDocumentModified(change);
                    break;
            }
        }

        notifyOnDataChanged();
    }

    private void onDocumentAdded(DocumentChange change) {
        Log.d(TAG, "Added: " + change.getNewIndex());

        // Add the document to the set
        mSnapshots.add(change.getNewIndex(), change.getDocument());
        notifyOnChildChanged(ChangeEventType.ADDED, change.getDocument(),
                change.getNewIndex(), -1);
    }

    private void onDocumentRemoved(DocumentChange change) {
        Log.d(TAG, "Removed: " + change.getOldIndex());

        // Invalidate snapshot cache (doc removed)
        mCache.invalidate(change.getDocument().getId());

        // Remove the document from the set
        mSnapshots.remove(change.getOldIndex());
        notifyOnChildChanged(ChangeEventType.REMOVED, change.getDocument(),
                -1, change.getOldIndex());
    }

    private void onDocumentModified(DocumentChange change) {
        // Invalidate snapshot cache (doc changed)
        mCache.invalidate(change.getDocument().getId());

        // Decide if the object was modified in place or if it moved
        if (change.getOldIndex() == change.getNewIndex()) {
            Log.d(TAG, "Modified (inplace): " + change.getOldIndex());

            mSnapshots.set(change.getOldIndex(), change.getDocument());
            notifyOnChildChanged(ChangeEventType.CHANGED, change.getDocument(),
                    change.getNewIndex(), change.getOldIndex());
        } else {
            Log.d(TAG, "Modified (moved): " + change.getOldIndex() + " --> " + change.getNewIndex());

            mSnapshots.remove(change.getOldIndex());
            mSnapshots.add(change.getNewIndex(), change.getDocument());
            notifyOnChildChanged(ChangeEventType.MOVED, change.getDocument(),
                    change.getNewIndex(), change.getOldIndex());
        }
    }

    @Override
    protected List<DocumentSnapshot> getSnapshots() {
        return mSnapshots;
    }

    private void startListening() {
        if (mRegistration != null) {
            Log.d(TAG, "startListening: already listening.");
            return;
        }

        mRegistration = mQuery.addSnapshotListener(this);
    }

    private void stopListening() {
        if (mRegistration != null) {
            mRegistration.remove();
            mRegistration = null;
        }

        mSnapshots.clear();
    }

    private void notifyOnChildChanged(ChangeEventType type,
                                      DocumentSnapshot snapshot,
                                      int newIndex, int oldIndex) {

        for (ChangeEventListener listener : getListeners()) {
            listener.onChildChanged(type, snapshot, newIndex, oldIndex);
        }
    }

    private void notifyOnError(FirebaseFirestoreException e) {
        for (ChangeEventListener listener : getListeners()) {
            listener.onError(e);
        }
    }

    private void notifyOnDataChanged() {
        for (ChangeEventListener listener : getListeners()) {
            listener.onDataChanged();
        }
    }
}
