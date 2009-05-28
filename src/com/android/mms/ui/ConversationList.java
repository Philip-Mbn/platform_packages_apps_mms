/*
 * Copyright (C) 2008 Esmertec AG.
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mms.ui;

import com.android.mms.R;
import com.android.mms.data.Contact;
import com.android.mms.data.ContactList;
import com.android.mms.data.Conversation;
import com.android.mms.transaction.MessagingNotification;
import com.android.mms.util.DraftCache;

import com.google.android.mms.pdu.PduHeaders;
import com.google.android.mms.util.SqliteWrapper;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.DialogInterface.OnClickListener;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.provider.Contacts;
import android.provider.Contacts.Intents.Insert;
import android.provider.Telephony.Mms;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnCreateContextMenuListener;
import android.view.View.OnKeyListener;
import android.widget.AdapterView;
import android.widget.ListView;

/**
 * This activity provides a list view of existing conversations.
 */
public class ConversationList extends ListActivity
            implements DraftCache.OnDraftChangedListener {
    private static final String TAG = "ConversationList";
    private static final boolean DEBUG = false;
    private static final boolean LOCAL_LOGV = DEBUG;

    private static final int THREAD_LIST_QUERY_TOKEN = 1701;

    private static final int DELETE_CONVERSATION_TOKEN = 1801;

    // IDs of the main menu items.
    public static final int MENU_COMPOSE_NEW          = 0;
    public static final int MENU_SEARCH               = 1;
    public static final int MENU_DELETE_ALL           = 3;
    public static final int MENU_PREFERENCES          = 4;

    // IDs of the context menu items for the list of conversations.
    public static final int MENU_DELETE               = 0;
    public static final int MENU_VIEW                 = 1;
    public static final int MENU_VIEW_CONTACT         = 2;
    public static final int MENU_ADD_TO_CONTACTS      = 3;

    private ThreadListQueryHandler mQueryHandler;
    private ConversationListAdapter mListAdapter;
    private CharSequence mTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.conversation_list_screen);

        mQueryHandler = new ThreadListQueryHandler(getContentResolver());

        ListView listView = getListView();
        LayoutInflater inflater = LayoutInflater.from(this);
        ConversationHeaderView headerView = (ConversationHeaderView)
                inflater.inflate(R.layout.conversation_header, listView, false);
        headerView.bind(getString(R.string.new_message),
                getString(R.string.create_new_message));
        listView.addHeaderView(headerView, null, true);

        listView.setOnCreateContextMenuListener(mConvListOnCreateContextMenuListener);
        listView.setOnKeyListener(mThreadListKeyListener);

        initListAdapter();

        handleCreationIntent(getIntent());
    }

    private void initListAdapter() {
        mListAdapter = new ConversationListAdapter(this, null);
        setListAdapter(mListAdapter);
    }

    static public boolean isFailedToDeliver(Intent intent) {
        return (intent != null) && intent.getBooleanExtra("undelivered_flag", false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // Handle intents that occur after the activity has already been created.
        handleCreationIntent(intent);
    }

    protected void handleCreationIntent(Intent intent) {
        // Handle intents that occur upon creation of the activity.
        initNormalQueryArgs();
   }

    @Override
    protected void onResume() {
        super.onResume();

        DraftCache.getInstance().addOnDraftChangedListener(this);

        Conversation.cleanup(this);

        // Make sure the draft cache is up to date.
        DraftCache.getInstance().refresh();

        startAsyncQuery();

        Contact.invalidateCache();
    }

    @Override
    protected void onPause() {
        super.onPause();

        DraftCache.getInstance().removeOnDraftChangedListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();

        mListAdapter.changeCursor(null);
    }

    public void onDraftChanged(long threadId, boolean hasDraft) {
        // Run notifyDataSetChanged() on the main thread.
        mQueryHandler.post(new Runnable() {
            public void run() {
                mListAdapter.notifyDataSetChanged();
            }
        });
    }

    private void initNormalQueryArgs() {
        mTitle = getString(R.string.app_label);
    }

    private void startAsyncQuery() {
        try {
            setTitle(getString(R.string.refreshing));
            setProgressBarIndeterminateVisibility(true);

            Conversation.startQueryForAll(mQueryHandler, THREAD_LIST_QUERY_TOKEN);
        } catch (SQLiteException e) {
            SqliteWrapper.checkSQLiteException(this, e);
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();

        menu.add(0, MENU_COMPOSE_NEW, 0, R.string.menu_compose_new).setIcon(
                com.android.internal.R.drawable.ic_menu_compose);
        // Removed search as part of b/1205708
        //menu.add(0, MENU_SEARCH, 0, R.string.menu_search).setIcon(
        //        R.drawable.ic_menu_search).setAlphabeticShortcut(SearchManager.MENU_KEY);
        if (mListAdapter.getCount() > 0) {
            menu.add(0, MENU_DELETE_ALL, 0, R.string.menu_delete_all).setIcon(
                    android.R.drawable.ic_menu_delete);
        }

        menu.add(0, MENU_PREFERENCES, 0, R.string.menu_preferences).setIcon(
                android.R.drawable.ic_menu_preferences);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case MENU_COMPOSE_NEW:
                createNewMessage();
                break;
            case MENU_SEARCH:
                onSearchRequested();
                break;
            case MENU_DELETE_ALL:
                confirmDeleteDialog(new DeleteThreadListener(-1L), true);
                break;
            case MENU_PREFERENCES: {
                Intent intent = new Intent(this, MessagingPreferenceActivity.class);
                startActivityIfNeeded(intent, -1);
                break;
            }
            default:
                return true;
        }
        return false;
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        if (LOCAL_LOGV) {
            Log.v(TAG, "onListItemClick: position=" + position + ", id=" + id);
        }

        if (position == 0) {
            createNewMessage();
        } else if (v instanceof ConversationHeaderView) {
            ConversationHeaderView headerView = (ConversationHeaderView) v;
            ConversationHeader ch = headerView.getConversationHeader();
            openThread(ch.getThreadId());
        }
    }

    private void createNewMessage() {
        Intent intent = new Intent(this, ComposeMessageActivity.class);
        startActivity(intent);
    }

    private void openThread(long threadId) {
        Intent intent = new Intent(this, ComposeMessageActivity.class);
        intent.putExtra("thread_id", threadId);
        startActivity(intent);
    }

    public static Intent createAddContactIntent(String address) {
        // address must be a single recipient
        Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
        intent.setType(Contacts.People.CONTENT_ITEM_TYPE);
        if (Mms.isEmailAddress(address)) {
            intent.putExtra(Insert.EMAIL, address);
        } else {
            intent.putExtra(Insert.PHONE, address);
        }

        return intent;
    }

    private final OnCreateContextMenuListener mConvListOnCreateContextMenuListener =
        new OnCreateContextMenuListener() {
        public void onCreateContextMenu(ContextMenu menu, View v,
                ContextMenuInfo menuInfo) {
            Cursor cursor = mListAdapter.getCursor();
            Conversation conv = Conversation.from(ConversationList.this, cursor);
            ContactList recipients = conv.getRecipients();
            menu.setHeaderTitle(recipients.formatNames(","));

            AdapterView.AdapterContextMenuInfo info =
                (AdapterView.AdapterContextMenuInfo) menuInfo;
            if (info.position > 0) {
                menu.add(0, MENU_VIEW, 0, R.string.menu_view);

                // Only show if there's a single recipient
                if (recipients.size() == 1) {
                    // do we have this recipient in contacts?
                    if (recipients.get(0).existsInDatabase()) {
                        menu.add(0, MENU_VIEW_CONTACT, 0, R.string.menu_view_contact);
                    } else {
                        menu.add(0, MENU_ADD_TO_CONTACTS, 0, R.string.menu_add_to_contacts);
                    }
                }
                menu.add(0, MENU_DELETE, 0, R.string.menu_delete);
            }
        }
    };

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        Cursor cursor = mListAdapter.getCursor();
        Conversation conv = Conversation.from(ConversationList.this, cursor);
        long threadId = conv.getThreadId();
        switch (item.getItemId()) {
            case MENU_DELETE: {
                DeleteThreadListener l = new DeleteThreadListener(threadId);
                confirmDeleteDialog(l, false);
                break;
            }
            case MENU_VIEW: {
                openThread(threadId);
                break;
            }
            case MENU_VIEW_CONTACT: {
                Contact contact = conv.getRecipients().get(0);
                Intent intent = new Intent(Intent.ACTION_VIEW, contact.getUri());
                startActivity(intent);
                break;
            }
            case MENU_ADD_TO_CONTACTS: {
                String address = conv.getRecipients().get(0).getNumber();
                startActivity(createAddContactIntent(address));
                break;
            }
            default:
                break;
        }

        return super.onContextItemSelected(item);
    }

    public void onConfigurationChanged(Configuration newConfig) {
        // We override this method to avoid restarting the entire
        // activity when the keyboard is opened (declared in
        // AndroidManifest.xml).  Because the only translatable text
        // in this activity is "New Message", which has the full width
        // of phone to work with, localization shouldn't be a problem:
        // no abbreviated alternate words should be needed even in
        // 'wide' languages like German or Russian.

        super.onConfigurationChanged(newConfig);
        if (DEBUG) Log.v(TAG, "onConfigurationChanged: " + newConfig);
    }

    private void confirmDeleteDialog(OnClickListener listener, boolean deleteAll) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.confirm_dialog_title);
        builder.setIcon(android.R.drawable.ic_dialog_alert);
        builder.setCancelable(true);
        builder.setPositiveButton(R.string.yes, listener);
        builder.setNegativeButton(R.string.no, null);
        builder.setMessage(deleteAll
                ? R.string.confirm_delete_all_conversations
                : R.string.confirm_delete_conversation);

        builder.show();
    }

    private final OnKeyListener mThreadListKeyListener = new OnKeyListener() {
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DEL: {
                        long id = getListView().getSelectedItemId();
                        if (id > 0) {
                            DeleteThreadListener l = new DeleteThreadListener(
                                    id);
                            confirmDeleteDialog(l, false);
                        }
                        return true;
                    }
                }
            }
            return false;
        }
    };

    private class DeleteThreadListener implements OnClickListener {
        private final long mThreadId;

        public DeleteThreadListener(long threadId) {
            mThreadId = threadId;
        }

        public void onClick(DialogInterface dialog, int whichButton) {
            MessageUtils.handleReadReport(ConversationList.this, mThreadId,
                    PduHeaders.READ_STATUS__DELETED_WITHOUT_BEING_READ, new Runnable() {
                public void run() {
                    int token = DELETE_CONVERSATION_TOKEN;
                    if (mThreadId == -1) {
                        Conversation.startDeleteAll(mQueryHandler, token);
                    } else {
                        Conversation.startDelete(mQueryHandler, token, mThreadId);
                    }
                }
            });
        }
    }

    private final class ThreadListQueryHandler extends AsyncQueryHandler {
        public ThreadListQueryHandler(ContentResolver contentResolver) {
            super(contentResolver);
        }

        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            switch (token) {
            case THREAD_LIST_QUERY_TOKEN:
                mListAdapter.changeCursor(cursor);
                setTitle(mTitle);
                setProgressBarIndeterminateVisibility(false);
                break;
            default:
                Log.e(TAG, "onQueryComplete called with unknown token " + token);
            }
        }

        @Override
        protected void onDeleteComplete(int token, Object cookie, int result) {
            switch (token) {
            case DELETE_CONVERSATION_TOKEN:
                // Update the notification for new messages since they
                // may be deleted.
                MessagingNotification.updateNewMessageIndicator(ConversationList.this);
                // Update the notification for failed messages since they
                // may be deleted.
                MessagingNotification.updateSendFailedNotification(ConversationList.this);

                // Make sure the list reflects the delete
                startAsyncQuery();

                onContentChanged();
                break;
            }
        }
    }
}
