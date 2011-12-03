/*******************************************************************************
 * Copyright (c) 2011 ETH Zurich.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors:
 *     Paolo Carta - Implementation
 *     Theus Hossmann - Implementation
 *     Dominik Schatzmann - Message specification
 ******************************************************************************/

package ch.ethz.twimight.net.twitter;

import ch.ethz.twimight.data.DBOpenHelper;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

/**
 * The content provider for Twitter users
 * @author thossmann
 *
 */
public class TwitterUsersContentProvider extends ContentProvider {

	private static final String TAG = "TwitterUsersContentProvider";
	
	private SQLiteDatabase database;
	private DBOpenHelper dbHelper;
	
	private static UriMatcher twitterusersUriMatcher;
	
	private static final int USERS = 1;
	private static final int USERS_ID = 2;
	
	private static final int USERS_FRIENDS = 4;
	private static final int USERS_FOLLOWERS = 5;
	
	
	// Here we define all the URIs this provider knows
	static{
		twitterusersUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
		twitterusersUriMatcher.addURI(TwitterUsers.TWITTERUSERS_AUTHORITY, TwitterUsers.TWITTERUSERS, USERS);
		
		twitterusersUriMatcher.addURI(TwitterUsers.TWITTERUSERS_AUTHORITY, TwitterUsers.TWITTERUSERS + "/#", USERS_ID);

		twitterusersUriMatcher.addURI(TwitterUsers.TWITTERUSERS_AUTHORITY, TwitterUsers.TWITTERUSERS + "/" + TwitterUsers.TWITTERUSERS_FRIENDS, USERS_FRIENDS);
		twitterusersUriMatcher.addURI(TwitterUsers.TWITTERUSERS_AUTHORITY, TwitterUsers.TWITTERUSERS + "/" + TwitterUsers.TWITTERUSERS_FOLLOWERS, USERS_FOLLOWERS);
		
	}
	
	/**
	 * onCreate we initialize and open the DB.
	 */
	@Override
	public boolean onCreate() {
		dbHelper = DBOpenHelper.getInstance(getContext());
		database = dbHelper.getWritableDatabase();

		return true;
	}

	/**
	 * Returns the MIME types (defined in TwitterUsers) of a URI
	 */
	@Override
	public String getType(Uri uri) {
		switch(twitterusersUriMatcher.match(uri)){
			case USERS: return TwitterUsers.TWITTERUSERS_CONTENT_TYPE;
			case USERS_ID: return TwitterUsers.TWITTERUSER_CONTENT_TYPE;
			case USERS_FRIENDS: return TwitterUsers.TWITTERUSERS_CONTENT_TYPE;
			case USERS_FOLLOWERS: return TwitterUsers.TWITTERUSERS_CONTENT_TYPE;
			default: throw new IllegalArgumentException("Unknown URI: " + uri);	
		}
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String where, String[] whereArgs, String sortOrder) {
		
		Log.i(TAG, "query: " + uri);
		
		if(TextUtils.isEmpty(sortOrder)) sortOrder = TwitterUsers.DEFAULT_SORT_ORDER;
		
		Intent i;
		
		Cursor c = null;
		switch(twitterusersUriMatcher.match(uri)){
			case USERS: 
				Log.i(TAG, "query matched... USERS");
				c = database.query(DBOpenHelper.TABLE_USERS, projection, where, whereArgs, null, null, sortOrder);
				// TODO: Notification URI
				c.setNotificationUri(getContext().getContentResolver(), TwitterUsers.CONTENT_URI);
				break;

			
			case USERS_ID: 
				Log.i(TAG, "query matched... USERS_ID");
				c = database.query(DBOpenHelper.TABLE_USERS, projection, "_id="+uri.getLastPathSegment(), whereArgs, null, null, sortOrder);
				c.setNotificationUri(getContext().getContentResolver(),uri);
				break;
			
			case USERS_FOLLOWERS:
				Log.i(TAG, "query matched... FOLLOWERS");
				c = database.query(DBOpenHelper.TABLE_USERS, projection, TwitterUsers.TWITTERUSERS_COLUMNS_FOLLOWING+">0 AND "+TwitterUsers.TWITTERUSERS_COLUMNS_SCREENNAME+" IS NOT NULL", whereArgs, null, null, sortOrder);
				c.setNotificationUri(getContext().getContentResolver(),uri);
				c.setNotificationUri(getContext().getContentResolver(), TwitterUsers.CONTENT_URI);
				
				// start synch service with a synch followers request
				i = new Intent(TwitterService.SYNCH_ACTION);
				i.putExtra("synch_request", TwitterService.SYNCH_FOLLOWERS);
				getContext().startService(i);
			
				break;
			case USERS_FRIENDS:
				Log.i(TAG, "query matched... FRIENDS");
				c = database.query(DBOpenHelper.TABLE_USERS, projection, TwitterUsers.TWITTERUSERS_COLUMNS_FOLLOW+">0 AND "+TwitterUsers.TWITTERUSERS_COLUMNS_SCREENNAME+" IS NOT NULL", whereArgs, null, null, sortOrder);
				c.setNotificationUri(getContext().getContentResolver(),TwitterUsers.CONTENT_URI);
				c.setNotificationUri(getContext().getContentResolver(),uri);
				// start synch service with a synch friends request
				i = new Intent(TwitterService.SYNCH_ACTION);
				i.putExtra("synch_request", TwitterService.SYNCH_FRIENDS);
				getContext().startService(i);
				
				break;

			default: throw new IllegalArgumentException("Unsupported URI: " + uri);	
		}
		
		return c;
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		Log.i(TAG, "insert: " + uri);

		if(twitterusersUriMatcher.match(uri) != USERS) throw new IllegalArgumentException("Unsupported URI: " + uri);
		
		if(checkValues(values)){
			// if we already have the user, we discard the request
			String[] projection = {"_id"};
			Cursor c = database.query(DBOpenHelper.TABLE_USERS, projection, TwitterUsers.TWITTERUSERS_COLUMNS_ID+"="+values.getAsLong(TwitterUsers.TWITTERUSERS_COLUMNS_ID), null, null, null, null);
			if(c.getCount()>0){
				Log.i(TAG, "already have user!");
				return null;
			}
			
			long rowId = database.insert(DBOpenHelper.TABLE_USERS, null, values);
			if(rowId >= 0){
				if(values.containsKey(TwitterUsers.TWITTERUSERS_COLUMNS_FLAGS))
				if(values.getAsInteger(TwitterUsers.TWITTERUSERS_COLUMNS_FLAGS)>0)
				{
					Intent i = new Intent(TwitterService.SYNCH_ACTION);
					i.putExtra("synch_request", TwitterService.SYNCH_USER);
					i.putExtra("rowId", rowId);
					getContext().startService(i);
				}

				Uri insertUri = ContentUris.withAppendedId(TwitterUsers.CONTENT_URI, rowId);
				//getContext().getContentResolver().notifyChange(TwitterUsers.CONTENT_URI, null);
				//getContext().getContentResolver().notifyChange(Tweets.CONTENT_URI, null);
				purge(DBOpenHelper.TABLE_USERS);
				Log.i(TAG, "User inserted! " + values);
				
				return insertUri;
			} else {
				throw new IllegalStateException("Could not insert user into database " + values);
			}
		} else {
			throw new IllegalArgumentException("Illegal user: " + values);
		}
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		Log.i(TAG, "update: " + uri);

		if(twitterusersUriMatcher.match(uri) != USERS_ID) throw new IllegalArgumentException("Unsupported URI: " + uri);
		
		if(checkValues(values)){
			int nrRows = database.update(DBOpenHelper.TABLE_USERS, values, "_id=" + uri.getLastPathSegment() , null);
			if(nrRows > 0){
				
				if(values.containsKey(TwitterUsers.TWITTERUSERS_COLUMNS_FLAGS))
				if(values.getAsInteger(TwitterUsers.TWITTERUSERS_COLUMNS_FLAGS)>0)
				{
					Intent i = new Intent(TwitterService.SYNCH_ACTION);
					i.putExtra("synch_request", TwitterService.SYNCH_USER);
					i.putExtra("rowId", new Long(uri.getLastPathSegment()));
					getContext().startService(i);
				}

				Log.i(TAG, "User updated! " + values);
				return nrRows;
			} else {
				throw new IllegalStateException("Could not insert user into database " + values);
			}
		} else {
			throw new IllegalArgumentException("Illegal user: " + values);
		}
	}
	
	@Override
	public int delete(Uri uri, String arg1, String[] arg2) {
		// TODO Auto-generated method stub
		return 0;
	}
	
	private void purge(String table){
		
	}
	
	private boolean checkValues(ContentValues values){
		return true;
	}


}
