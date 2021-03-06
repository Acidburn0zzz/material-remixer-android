/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.libraries.remixer.storage;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;
import com.google.android.libraries.remixer.Remixer;
import com.google.android.libraries.remixer.Variable;
import com.google.android.libraries.remixer.serialization.StoredVariable;
import com.google.android.libraries.remixer.sync.LocalValueSyncing;
import com.google.android.libraries.remixer.sync.SynchronizationMechanism;
import com.google.firebase.FirebaseApp;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A {@link SynchronizationMechanism} that syncs up to a firebase Remote Controller.
 *
 * <p>This synchronization mechanism assumes that the local host is the source of truth, so it does
 * very little in terms of conflict resolution.
 */
public class FirebaseRemoteControllerSyncer
    extends LocalStorage implements ChildEventListener {

  private final Context applicationContext;
  /**
   * The reference to the root element for this Remixer Instance in the database
   */
  private DatabaseReference reference;
  /**
   * The current context that is active on the app (foreground activity).
   */
  private WeakReference<Object> context;
  /**
   * Objects that are listening to changes on the sharing status.
   */
  private final HashSet<WeakReference<SharingStatusListener>> listeners;

  /**
   * Whether sharing is enabled or not. All accesses to this field must be synchronized.
   */
  private boolean sharing = false;

  /**
   * The autogenerated id for this instance of Remixer. This is the first 8 characters of a random
   * UUID that would identify this device.
   */
  private String remoteId;

  private static final String PREFERENCES_FILE_NAME = "remixer_firebase";
  private static final String REMOTE_ID = "remote_id";
  private static final String REFERENCE_FORMAT = "remixer/%s";

  /**
   * Initializes a {@code FirebaseRemoteControllerSyncer} instance.
   *
   * <p>Uses {@code context} to get the Remote ID from shared preferences (or persist a generated
   * one if it does not yet exist).
   */
  public FirebaseRemoteControllerSyncer(Context applicationContext) {
    super(applicationContext);
    SharedPreferences preferences =
        applicationContext.getSharedPreferences(PREFERENCES_FILE_NAME, Context.MODE_PRIVATE);
    remoteId = preferences.getString(REMOTE_ID, null);
    if (remoteId == null) {
      remoteId = UUID.randomUUID().toString().substring(0, 7);
      preferences.edit().putString(REMOTE_ID, remoteId).apply();
    }
    this.applicationContext = applicationContext;
    listeners = new HashSet<>();
  }

  /**
   * Starts sharing up to Firebase.
   *
   * <p>Since we don't know what the status of the database is right now, we clear the value and
   * then add everything for the current context, then add this instance as a child event listener.
   *
   * <p>That way we get notified of changes to children (individual variables) after the initial
   * sync.
   */
  public synchronized void startSharing() {
    reference = FirebaseDatabase.getInstance().getReference(
        String.format(Locale.getDefault(), REFERENCE_FORMAT, remoteId));
    reference.removeValue();
    sharing = true;
    if (context != null && context.get() != null) {
      List<Variable> variableList =  Remixer.getInstance().getVariablesWithContext(context.get());
      if (variableList != null) {
        for (Variable variable : variableList) {
          syncVariableToRemoteController(StoredVariable.fromVariable(variable));
        }
      }
    }
    reference.addChildEventListener(this);
    notifyListeners();
  }

  /**
   * Stops sharing to firebase, removes this instance as listener for changes and clears the values
   * in Firebase.
   */
  public synchronized void stopSharing() {
    if (sharing) {
      sharing = false;
      reference.removeEventListener(this);
      reference.removeValue();
    }
    notifyListeners();
  }

  private void notifyListeners() {
    ArrayList<WeakReference<SharingStatusListener>> remove = null;
    for (WeakReference<SharingStatusListener> reference : listeners) {
      if (reference.get() == null) {
        if (remove == null) {
          remove = new ArrayList<>();
        }
        remove.add(reference);
      } else {
        reference.get().updateSharingStatus(sharing);
      }
    }
    if (remove != null) {
      for (WeakReference<SharingStatusListener> reference : remove) {
        listeners.remove(reference);
      }
    }
  }

  /**
   * Returns true if it is currently sharing up to firebase.
   */
  public boolean isSharing() {
    return sharing;
  }

  /**
   * Syncs a variable up to the remote controller.
   *
   * <p>Since the local app is the source of truth, this ignores any differences there may be
   * between the local data and the remote data and just rewrites any remote data. This should not
   * happen in practice though, since variables in remote controllers are tied to a single instance
   * of the app (one specific device running the app).
   */
  private synchronized void syncVariableToRemoteController(StoredVariable variable) {
    if (sharing) {
      reference.child(variable.getKey()).setValue(variable);
    }
  }

  // Overrides from LocalValueSyncing
  @Override
  public void onAddingVariable(Variable variable) {
    super.onAddingVariable(variable);
    syncVariableToRemoteController(StoredVariable.fromVariable(variable));
  }

  @Override
  public void onValueChanged(Variable variable) {
    super.onValueChanged(variable);
    syncVariableToRemoteController(StoredVariable.fromVariable(variable));
  }

  @Override
  public void onContextChanged(Object currentContext) {
    super.onContextChanged(currentContext);
    if ((context == null && currentContext != null) ||
        (context != null && currentContext != context.get())) {
      context = new WeakReference<Object>(currentContext);
      if (reference != null) {
        reference.removeValue();
      }
      List<Variable> variables = Remixer.getInstance().getVariablesWithContext(currentContext);
      if (variables != null) {
        for (Variable<?> var : variables) {
          syncVariableToRemoteController(StoredVariable.fromVariable(var));
        }
      }
    }
  }

  @Override
  public void onContextRemoved(Object currentContext) {
    super.onContextRemoved(currentContext);
    if (context != null && context.get() == currentContext) {
      if (reference != null) {
        reference.removeValue();
      }
      context = null;
    }
  }

  // Implementation of ChildEventListener
  @Override
  public void onChildAdded(DataSnapshot dataSnapshot, String childKey) {
    // Add it to serializableRemixerContents if it does not exist. If it does exist let the local
    // value take precedence and ignore the one coming from firebase.
    if (!serializableRemixerContents.keySet().contains(childKey)) {
      serializableRemixerContents.addItem(
          FirebaseSerializationHelper.deserializeStoredVariable(dataSnapshot));
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  // There is no way to check this without knowing the type in advance
  public void onChildChanged(DataSnapshot dataSnapshot, String childKey) {
    StoredVariable storedVariable =
        FirebaseSerializationHelper.deserializeStoredVariable(dataSnapshot);
    serializableRemixerContents.setValue(storedVariable);
    Object valueFromFirebase = Remixer.getDataType(storedVariable.getDataType())
        .getConverter().toRuntimeType(storedVariable.getSelectedValue());
    for (Variable variable : Remixer.getInstance().getVariablesWithKey(storedVariable.getKey())) {
      variable.setValueWithoutNotifyingOthers(valueFromFirebase);
    }
  }

  @Override
  public void onChildRemoved(DataSnapshot dataSnapshot) {
    // This shouldn't happen.
  }

  @Override
  public void onChildMoved(DataSnapshot dataSnapshot, String childKey) {
    // This shouldn't happen.
  }

  @Override
  public void onCancelled(DatabaseError databaseError) {
    // Ignoring errors for the time being
  }

  public void addSharingStatusListener(@NonNull SharingStatusListener listener) {
    listener.updateSharingStatus(sharing);
    listeners.add(new WeakReference<>(listener));
  }

  public Intent getShareLinkIntent() {
    Intent intent = new Intent();
    intent.setAction(Intent.ACTION_SEND);
    String url = getRemoteUrl();
    String message =
        applicationContext.getResources().getString(R.string.shareLinkFormat, url);
    intent.putExtra(Intent.EXTRA_TEXT, message);
    intent.setType("text/plain");
    return intent;
  }

  /**
   * Returns the URL that points to the remote controller.
   */
  public String getRemoteUrl() {
    return String.format(
        Locale.getDefault(),
        "https://%s.firebaseapp.com/%s",
        getUrlPrefix(),
        remoteId);
  }

  private String getUrlPrefix() {
    String url = FirebaseApp.getInstance().getOptions().getStorageBucket();
    if (url.contains(".")) {
      return url.substring(0, url.indexOf('.'));
    }
    return url;
  }

  /**
   * Implement this to register as a listener for changes in the sharing status using
   * {@link #addSharingStatusListener(SharingStatusListener)}.
   */
  public interface SharingStatusListener {

    /**
     * Notify that the FirebaseRemoteControllerSyncer has started or stopped sharing.
     */
    void updateSharingStatus(boolean sharing);
  }
}
