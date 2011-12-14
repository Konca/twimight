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

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;

import winterwell.jtwitter.OAuthSignpostClient;
import winterwell.jtwitter.Twitter;
import winterwell.jtwitter.Twitter.Message;
import winterwell.jtwitter.TwitterAccount;
import winterwell.jtwitter.TwitterException;
import winterwell.jtwitter.Twitter.User;

import ch.ethz.bluetest.credentials.Obfuscator;
import ch.ethz.twimight.activities.LoginActivity;
import ch.ethz.twimight.activities.NewDMActivity;
import ch.ethz.twimight.activities.ShowDMUsersListActivity;
import ch.ethz.twimight.activities.ShowTweetListActivity;
import ch.ethz.twimight.activities.ShowUserListActivity;
import ch.ethz.twimight.util.Constants;

import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

/**
 * The service to send all kinds of API calls to Twitter. 
 * This is the only place where calling Twitter is allowed!
 * @author thossman
 *
 */
public class TwitterService extends Service {
	
	private static final String TAG = "TwitterService";
	
	public static final String SYNCH_ACTION = "twimight_synch";
	
	public static final int SYNCH_LOGIN = 0;
	public static final int SYNCH_ALL = 1;
	public static final int SYNCH_TIMELINE = 2;
	public static final int SYNCH_FAVORITES = 3;
	public static final int SYNCH_MENTIONS = 4;
	public static final int SYNCH_SEARCH = 5;
	public static final int SYNCH_FRIENDS = 7;
	public static final int SYNCH_FOLLOWERS = 8;
	public static final int SYNCH_USER = 9;
	public static final int SYNCH_TWEET = 10;
	public static final int SYNCH_DMS = 11;
	public static final int SYNCH_DM = 12;
	
	Twitter twitter;

	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}
	
	/**
	 * Executed when the service is started. We return START_STICKY to not be stopped immediately.
	 */
	@Override
	public int onStartCommand(Intent intent, int flags, int startId){
		
		
		// Do we have connectivity?
		ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
		if(cm.getActiveNetworkInfo()==null || !cm.getActiveNetworkInfo().isConnected()){
			Log.w(TAG, "Error synching: no connectivity");
			return START_NOT_STICKY;
		} 
		
		// Create twitter object
		String token = LoginActivity.getAccessToken(this);
		String secret = LoginActivity.getAccessTokenSecret(this);
		if(!(token == null || secret == null) ) {
			// get ready for OAuth
			OAuthSignpostClient client = new OAuthSignpostClient(Obfuscator.getKey(), Obfuscator.getSecret(), token, secret);
			twitter = new Twitter(null, client);
		} else {
			Log.e(TAG, "Error synching: no access token or secret");
			return START_NOT_STICKY;
		}
		
		
		// check what we are asked to synch
		int synchRequest = intent.getIntExtra("synch_request", SYNCH_ALL);
		switch(synchRequest){
		case SYNCH_LOGIN:
			synchLogin();
			break;
		case SYNCH_ALL:
			synchTransactionalTweets();
			synchTransactionalMessages();
			synchTransactionalUsers();
			synchTimeline();
			synchMentions();
			synchFavorites();
			synchMessages();
			break;
		case SYNCH_TIMELINE:
			synchTimeline();
			break;
		case SYNCH_MENTIONS:
			synchMentions();
			break;
		case SYNCH_FAVORITES:
			Log.i(TAG, "SYNCH_FAVORITES");
			synchFavorites();
			break;
		case SYNCH_FRIENDS:
			Log.i(TAG, "SYNCH_FRIENDS");
			synchFriends();
			break;
		case SYNCH_FOLLOWERS:
			Log.i(TAG, "SYNCH_FOLLOWERS");
			synchFollowers();
			break;
		case SYNCH_SEARCH:
			Log.i(TAG, "SYNCH_SEARCH");
			// TODO
			break;
		case SYNCH_USER:
			Log.i(TAG, "SYNCH_USER");
			if(intent.getLongExtra("rowId", 0) != 0){
				synchUser(intent.getLongExtra("rowId", 0));
			}
			break;
		case SYNCH_TWEET:
			Log.i(TAG, "SYNCH_TWEET");
			if(intent.getLongExtra("rowId", 0) != 0){
				synchTweet(intent.getLongExtra("rowId", 0));
			}
			break;
		case SYNCH_DMS:
			Log.i(TAG, "SYNCH_DMS");
			synchMessages();
			break;
		case SYNCH_DM:
			Log.i(TAG, "SYNCH_DM");
			if(intent.getLongExtra("rowId", 0) != 0){
				synchMessage(intent.getLongExtra("rowId", 0));
			}
			break;
		default:
			throw new IllegalArgumentException("Exception: Unknown synch request");
		}
		
		return START_STICKY;
	}

	/**
	 * Creates the thread to update friends
	 */
	private void synchFriends() {
		Log.i(TAG, "SYNCH_FRIENDS");
		if(System.currentTimeMillis() - getLastFriendsUpdate(getBaseContext()) > Constants.FRIENDS_MIN_SYNCH){
			(new UpdateFriendsTask()).execute();
		} else {
			Log.i(TAG, "Last friends synch too recent.");
		}

		
	}

	/**
	 * Creates the thread to update followers
	 */
	private void synchFollowers() {
		Log.i(TAG, "SYNCH_FOLLOWERS");
		if(System.currentTimeMillis() - getLastFollowerUpdate(getBaseContext()) > Constants.FOLLOWERS_MIN_SYNCH){
			(new UpdateFollowersTask()).execute();
		} else {
			Log.i(TAG, "Last followers synch too recent.");
		}		
	}

	/**
	 * Verifies the user credentials by a verifyCredentials call.
	 * Stores the user ID in the shared preferences.
	 */
	private void synchLogin(){
		Log.i(TAG, "SYNCH_LOGIN");
		(new VerifyCredentialsTask()).execute(Constants.LOGIN_ATTEMPTS);
	}

	/**
	 * Syncs all tweets which have transactional flags set
	 */
	private void synchTransactionalTweets(){
		Log.i(TAG, "SYNCH_TRANSACTIONAL_TWEETS");
		// get the flagged tweets
		Uri queryUri = Uri.parse("content://"+Tweets.TWEET_AUTHORITY+"/"+Tweets.TWEETS);
		Cursor c = getContentResolver().query(queryUri, null, Tweets.COL_FLAGS+"!=0", null, null);
		Log.i(TAG, c.getCount()+" transactional tweets to synch");
		if(c.getCount() >= 0){
			c.moveToFirst();
			while(!c.isAfterLast()){
				synchTweet(c.getLong(c.getColumnIndex("_id")));
				c.moveToNext();
			}
		}
		c.close();
	}
	
	/**
	 * Syncs all messages which have transactional flags set
	 */
	private void synchTransactionalMessages(){
		Log.i(TAG, "SYNCH_TRANSACTIONAL_MESSAGES");
		// get the flagged messages
		Uri queryUri = Uri.parse("content://"+DirectMessages.DM_AUTHORITY+"/"+DirectMessages.DMS);
		Cursor c = getContentResolver().query(queryUri, null, DirectMessages.COL_FLAGS+"!=0", null, null);
		Log.i(TAG, c.getCount()+" transactional messages to synch");
		if(c.getCount() >= 0){
			c.moveToFirst();
			while(!c.isAfterLast()){
				synchMessage(c.getLong(c.getColumnIndex("_id")));
				c.moveToNext();
			}
		}
		c.close();
	}
	
	/**
	 * Syncs all users which have transactional flags set
	 */
	private void synchTransactionalUsers(){
		Log.i(TAG, "SYNCH_TRANSACTIONAL_USERS");
		// get the flagged users
		Uri queryUri = Uri.parse("content://"+TwitterUsers.TWITTERUSERS_AUTHORITY+"/"+TwitterUsers.TWITTERUSERS);
		Cursor c = getContentResolver().query(queryUri, null, TwitterUsers.COL_FLAGS+"!=0", null, null);
		Log.i(TAG, c.getCount()+" transactional users to synch");
		if(c.getCount() >= 0){
			c.moveToFirst();
			while(!c.isAfterLast()){
				synchUser(c.getLong(c.getColumnIndex("_id")));
				c.moveToNext();
			}
		}
		c.close();
	}
	
	/**
	 * Checks the transactional flags of the tweet with the given _id and performs the corresponding actions
	 */
	private void synchTweet(long rowId) {
		Log.i(TAG, "SYNCH_TWEET");
		// get the flags
		Uri queryUri = Uri.parse("content://"+Tweets.TWEET_AUTHORITY+"/"+Tweets.TWEETS+"/"+rowId);
		Cursor c = getContentResolver().query(queryUri, null, null, null, null);
		
		if(c.getCount() == 0){
			Log.w(TAG, "Synch Tweet: Tweet not found " + rowId);
			return;
		}
		c.moveToFirst();
		
		int flags = c.getInt(c.getColumnIndex(Tweets.COL_FLAGS));
		if((flags & Tweets.FLAG_TO_DELETE)>0) {
			// Delete a tweet from twitter
			(new DestroyStatusTask()).execute(rowId);
		} else if((flags & Tweets.FLAG_TO_INSERT)>0) {
			// post the tweet to twitter
			(new UpdateStatusTask()).execute(rowId);
		} else if((flags & Tweets.FLAG_TO_FAVORITE)>0) {
			// post favorite to twitter
			(new FavoriteStatusTask()).execute(rowId);
		} else if((flags & Tweets.FLAG_TO_UNFAVORITE)>0) {
			// remove favorite from twitter
			(new UnfavoriteStatusTask()).execute(rowId);
		} else if((flags & Tweets.FLAG_TO_RETWEET)>0) {
			// retweet
			(new RetweetStatusTask()).execute(rowId);
		} 
		c.close();
	}
	
	/**
	 * Checks the transactional flags of the user with the given _id and performs the corresponding actions
	 */
	private void synchUser(long rowId) {
		Log.i(TAG, "SYNCH_USER");
		// get the flags
		Uri queryUri = Uri.parse("content://"+TwitterUsers.TWITTERUSERS_AUTHORITY+"/"+TwitterUsers.TWITTERUSERS+"/"+rowId);
		Cursor c = getContentResolver().query(queryUri, null, null, null, null);
		
		if(c.getCount() == 0){
			Log.w(TAG, "Synch User: User not found " + rowId);
			return;
		}
		c.moveToFirst();
		
		int flags = c.getInt(c.getColumnIndex(TwitterUsers.COL_FLAGS));
		
		if((flags & TwitterUsers.FLAG_TO_UPDATE)>0) {
			// Update a user if it's time to do so
			if(c.isNull(c.getColumnIndex(TwitterUsers.COL_LASTUPDATE)) | (System.currentTimeMillis() - c.getInt(c.getColumnIndex(TwitterUsers.COL_LASTUPDATE))>Constants.USERS_MIN_SYNCH)){
				(new UpdateUserTask()).execute(rowId);
			} else {
				Log.i(TAG, "Last user update too recent");
			}
		} 
		if((flags & TwitterUsers.FLAG_TO_FOLLOW)>0) {
			// Follow a user
			(new FollowUserTask()).execute(rowId);
		} 
		if((flags & TwitterUsers.FLAG_TO_UNFOLLOW)>0) {
			// Unfollow a user
			(new UnfollowUserTask()).execute(rowId);
		} 
		if((flags & TwitterUsers.FLAG_TO_UPDATEIMAGE)>0){
			// load the profile image
			(new UpdateProfileImageTask()).execute(rowId);
		}
		c.close();
	}
	
	/**
	 * Starts a thread to load the timeline. But only if the last timeline request is old enough.
	 */
	private void synchTimeline() {
		
		Log.i(TAG, "SYNCH_TIMELINE");
		if(System.currentTimeMillis() - getLastTimelineUpdate(getBaseContext()) > Constants.TIMELINE_MIN_SYNCH){
			(new UpdateTimelineTask()).execute();
		} else {
			Log.i(TAG, "Last timeline synch too recent.");
		}
		
	}
	
	/**
	 * Starts a thread to load the favorites. But only if the last favorites request is old enough.
	 */
	private void synchFavorites() {
		Log.i(TAG, "SYNCH_FAVORITES");
		if(System.currentTimeMillis() - getLastFavoritesUpdate(getBaseContext()) > Constants.FAVORITES_MIN_SYNCH){
			(new UpdateFavoritesTask()).execute();
		} else {
			Log.i(TAG, "Last favorites synch too recent.");
		}
		
	}
	
	/**
	 * Starts a thread to load the mentions. But only if the last mentions request is old enough.
	 */
	private void synchMentions() {
		Log.i(TAG, "SYNCH_MENTIONS");
		if(System.currentTimeMillis() - getLastMentionsUpdate(getBaseContext()) > Constants.MENTIONS_MIN_SYNCH){
			(new UpdateMentionsTask()).execute();
		} else {
			Log.i(TAG, "Last mentions synch too recent.");
		}
		
	}
	
	/**
	 * Loads DMs from Twitter
	 */
	private void synchMessages() {
		Log.i(TAG, "SYNCH_MESSAGES");
		if(System.currentTimeMillis() - getLastDMsInUpdate(getBaseContext()) > Constants.DMS_MIN_SYNCH){
			(new UpdateDMsInTask()).execute(3); // maximum three attempts before we give up
		} else {
			Log.i(TAG, "Last DM IN synch too recent.");
		}
		
		if(System.currentTimeMillis() - getLastDMsOutUpdate(getBaseContext()) > Constants.DMS_MIN_SYNCH){
			(new UpdateDMsOutTask()).execute(3); // maximum three attempts before we give up
		} else {
			Log.i(TAG, "Last DM Out synch too recent.");
		}

		
	}
	
	/**
	 * Checks the transactional flags of the direct message with the given _id and performs the corresponding actions
	 */
	private void synchMessage(long rowId) {
		Log.i(TAG, "SYNCH_DM");
		// get the flags
		Uri queryUri = Uri.parse("content://"+DirectMessages.DM_AUTHORITY+"/"+DirectMessages.DMS+"/"+rowId);
		Cursor c = getContentResolver().query(queryUri, null, null, null, null);
		
		if(c.getCount() == 0){
			Log.w(TAG, "Synch Message: Message not found " + rowId);
			return;
		}
		c.moveToFirst();
		
		int flags = c.getInt(c.getColumnIndex(DirectMessages.COL_FLAGS));
		if((flags & DirectMessages.FLAG_TO_DELETE)>0) {
			// Delete the DM from twitter
			// TODO
			//(new DestroyMessageTask()).execute(rowId);
		} else if((flags & DirectMessages.FLAG_TO_INSERT)>0) {
			// post the DM to twitter
			(new SendMessageTask()).execute(rowId);
		} 
		c.close();
	}
	
	/**
	 * Reads the ID of the last tweet from shared preferences.
	 * @return
	 */
	public static BigInteger getTimelineSinceId(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		String sinceIdString = prefs.getString("timelineSinceId", null); 
		if(sinceIdString==null)
			return null;
		else
			return new BigInteger(sinceIdString);
	}

	/**
	 * Stores the given ID as the since ID
	 */
	public static void setTimelineSinceId(BigInteger sinceId, Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		SharedPreferences.Editor prefEditor = prefs.edit();
		prefEditor.putString("timelineSinceId",sinceId==null?null:sinceId.toString());
		prefEditor.commit();
	}

	/**
	 * Reads the timestamp of the last timeline update from shared preferences.
	 * @return
	 */
	public static long getLastTimelineUpdate(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		return prefs.getLong("timelineLastUpdate", 0);
	}
	
	/**
	 * Stores the current timestamp as the time of last timeline update
	 */
	public static void setLastTimelineUpdate(Date date, Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		SharedPreferences.Editor prefEditor = prefs.edit();
		if(date!=null)
			prefEditor.putLong("timelineLastUpdate", date.getTime());
		else
			prefEditor.putLong("timelineLastUpdate", 0);
		prefEditor.commit();
	}
	
	/**
	 * Reads the ID of the last favorites tweet from shared preferences.
	 * @return
	 */
	public static BigInteger getFavoritesSinceId(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		String sinceIdString = prefs.getString("favoritesSinceId", null); 
		if(sinceIdString==null)
			return null;
		else
			return new BigInteger(sinceIdString);
	}

	/**
	 * Stores the given ID as the since ID
	 */
	public static void setFavoritesSinceId(BigInteger sinceId, Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		SharedPreferences.Editor prefEditor = prefs.edit();
		prefEditor.putString("favoritesSinceId",sinceId==null?null:sinceId.toString());
		prefEditor.commit();
	}

	/**
	 * Reads the timestamp of the last favorites update from shared preferences.
	 * @return
	 */
	public static long getLastFavoritesUpdate(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		return prefs.getLong("favoritesLastUpdate", 0);
	}
	
	/**
	 * Stores the current timestamp as the time of last favorites update
	 */
	public static void setLastFavoritesUpdate(Date date, Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		SharedPreferences.Editor prefEditor = prefs.edit();
		if(date!=null)
			prefEditor.putLong("favoritesLastUpdate", date.getTime());
		else
			prefEditor.putLong("favoritesLastUpdate", 0);

		prefEditor.commit();
	}

	/**
	 * Reads the ID of the last mentions tweet from shared preferences.
	 * @return
	 */
	public static BigInteger getMentionsSinceId(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		String sinceIdString = prefs.getString("mentionsSinceId", null); 
		if(sinceIdString==null)
			return null;
		else
			return new BigInteger(sinceIdString);
	}

	/**
	 * Stores the given ID as the since ID
	 */
	public static void setMentionsSinceId(BigInteger sinceId, Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		SharedPreferences.Editor prefEditor = prefs.edit();
		prefEditor.putString("mentionsSinceId",sinceId==null?null:sinceId.toString());
		prefEditor.commit();
	}

	/**
	 * Reads the timestamp of the last mentions update from shared preferences.
	 * @return
	 */
	public static long getLastMentionsUpdate(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		return prefs.getLong("mentionsLastUpdate", 0);
	}
	
	/**
	 * Stores the current timestamp as the time of last mentions update
	 */
	public static void setLastMentionsUpdate(Date date, Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		SharedPreferences.Editor prefEditor = prefs.edit();
		if(date!=null)
			prefEditor.putLong("mentionsLastUpdate", date.getTime());
		else
			prefEditor.putLong("mentionsLastUpdate", 0);

		prefEditor.commit();
	}
	
	/**
	 * Reads the timestamp of the last friends update from shared preferences.
	 * @return
	 */
	public static long getLastFriendsUpdate(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		return prefs.getLong("friendsLastUpdate", 0);
	}
	
	/**
	 * Stores the current timestamp as the time of last friends update
	 */
	public static void setLastFriendsUpdate(Date date, Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		SharedPreferences.Editor prefEditor = prefs.edit();
		if(date!=null)
			prefEditor.putLong("friendsLastUpdate", date.getTime());
		else
			prefEditor.putLong("friendsLastUpdate", 0);

		prefEditor.commit();
	}
	
	/**
	 * Reads the timestamp of the last follower update from shared preferences.
	 * @return
	 */
	public static long getLastFollowerUpdate(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		return prefs.getLong("followerLastUpdate", 0);
	}
	
	/**
	 * Stores the current timestamp as the time of last follower update
	 */
	public static void setLastFollowerUpdate(Date date, Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		SharedPreferences.Editor prefEditor = prefs.edit();
		if(date!=null)
			prefEditor.putLong("followerLastUpdate", date.getTime());
		else
			prefEditor.putLong("followerLastUpdate", 0);

		prefEditor.commit();
	}
	
	/**
	 * Reads the timestamp of the last DM (incoming) update from shared preferences.
	 * @return
	 */
	public static long getLastDMsInUpdate(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		return prefs.getLong("DMsInLastUpdate", 0);
	}
	
	/**
	 * Stores the current timestamp as the time of last DM update
	 */
	public static void setLastDMsInUpdate(Date date, Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		SharedPreferences.Editor prefEditor = prefs.edit();
		if(date!=null)
			prefEditor.putLong("DMsInLastUpdate", date.getTime());
		else
			prefEditor.putLong("DMsInLastUpdate", 0);

		prefEditor.commit();
	}
	
	/**
	 * Reads the ID of the last incoming direct message
	 */
	public BigInteger getDMsInSinceId(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		String sinceIdString = prefs.getString("DMsInSinceId", null); 
		if(sinceIdString==null)
			return null;
		else
			return new BigInteger(sinceIdString);	}

	/**
	 * Stores the provided ID as the last incoming DM
	 * @param lastId
	 * @param baseContext
	 */
	public static void setDMsInSinceId(BigInteger sinceId, Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		SharedPreferences.Editor prefEditor = prefs.edit();
		prefEditor.putString("DMsInSinceId",sinceId==null?null:sinceId.toString());
		prefEditor.commit();		
	}
	
	/**
	 * Reads the timestamp of the last DM (outgoing) update from shared preferences.
	 * @return
	 */
	public static long getLastDMsOutUpdate(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		return prefs.getLong("DMsOutLastUpdate", 0);
	}
	
	/**
	 * Stores the current timestamp as the time of last DM (outgoing) update
	 */
	public static void setLastDMsOutUpdate(Date date, Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		SharedPreferences.Editor prefEditor = prefs.edit();
		if(date!=null)
			prefEditor.putLong("DMsOutLastUpdate", date.getTime());
		else
			prefEditor.putLong("DMsOutLastUpdate", 0);

		prefEditor.commit();
	}
	
	/**
	 * Reads the ID of the last outgoing direct message
	 */
	public BigInteger getDMsOutSinceId(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		String sinceIdString = prefs.getString("DMsOutSinceId", null); 
		if(sinceIdString==null)
			return null;
		else
			return new BigInteger(sinceIdString);	}

	/**
	 * Stores the provided ID as the last outgoing DM
	 * @param lastId
	 * @param baseContext
	 */
	public static void setDMsOutSinceId(BigInteger sinceId, Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		SharedPreferences.Editor prefEditor = prefs.edit();
		prefEditor.putString("DMsOutSinceId",sinceId==null?null:sinceId.toString());
		prefEditor.commit();		
	}
	
	/**
	 * Updates a tweet in the DB (or inserts it if the tweet is new to us)
	 */
	private int updateTweet(Twitter.Status tweet, int buffer){
		// do we already have the tweet?
		Uri queryUri = Uri.parse("content://"+Tweets.TWEET_AUTHORITY+"/"+Tweets.TWEETS);
		String[] projection = {"_id", Tweets.COL_BUFFER};
		
		Cursor c = getContentResolver().query(queryUri, projection, Tweets.COL_TID+"="+tweet.getId(), null, null);

		int tweetId = 0;
		
		if(c.getCount()==0){
			// insert URI
			Uri insertUri = Uri.parse("content://"+Tweets.TWEET_AUTHORITY+"/"+Tweets.TWEETS+"/"+Tweets.TWEETS_TABLE_TIMELINE + "/" + Tweets.TWEETS_SOURCE_NORMAL);
			Uri resultUri = getContentResolver().insert(insertUri, getTweetContentValues(tweet, buffer));
			tweetId = new Integer(resultUri.getLastPathSegment());
			c.close();
		} else {
			c.moveToFirst();
			Uri updateUri = Uri.parse("content://" + Tweets.TWEET_AUTHORITY + "/" + Tweets.TWEETS + "/" + c.getInt(c.getColumnIndex("_id")));
			int updatedBufferFlags = buffer | c.getInt(c.getColumnIndex(Tweets.COL_BUFFER));
			getContentResolver().update(updateUri, getTweetContentValues(tweet, updatedBufferFlags), null, null);
			tweetId = c.getInt(c.getColumnIndex("_id"));
			c.close();

		}
		
		return tweetId;
	}
	
	/**
	 * Updates a direct message in the DB (or inserts it if the message is new to us)
	 */
	private int updateMessage(Twitter.Message dm, int buffer){
		// do we already have the tweet?
		Uri queryUri = Uri.parse("content://"+DirectMessages.DM_AUTHORITY+"/"+DirectMessages.DMS);
		String[] projection = {"_id", DirectMessages.COL_BUFFER};
		
		Cursor c = getContentResolver().query(queryUri, projection, DirectMessages.COL_DMID+"="+dm.getId(), null, null);

		int dmId = 0;
		
		if(c.getCount()==0){
			// insert URI
			Uri insertUri = Uri.parse("content://"+DirectMessages.DM_AUTHORITY +"/"+ DirectMessages.DMS + "/" + DirectMessages.DMS_LIST + "/" + DirectMessages.DMS_SOURCE_NORMAL);
			Uri resultUri = getContentResolver().insert(insertUri, getMessageContentValues(dm, buffer));
			dmId = new Integer(resultUri.getLastPathSegment());
		} else {
			c.moveToFirst();
			Uri updateUri = Uri.parse("content://" + DirectMessages.DM_AUTHORITY + "/" + DirectMessages.DMS + "/" + c.getInt(c.getColumnIndex("_id")));
			int updatedBufferFlags = buffer | c.getInt(c.getColumnIndex(DirectMessages.COL_BUFFER));
			getContentResolver().update(updateUri, getMessageContentValues(dm, updatedBufferFlags), null, null);
			dmId = c.getInt(c.getColumnIndex("_id"));

		}
		c. close();
		
		return dmId;
	}
	

	/**
	 * Updates the user profile in the DB. Flags the users for updating their profile images.
	 * @param user
	 */
	private long updateUser(Twitter.User user) {
		
		if(user==null || user.getId()==null) return 0;
		
		// do we already have the user?
		Uri uri = Uri.parse("content://" + TwitterUsers.TWITTERUSERS_AUTHORITY + "/" + TwitterUsers.TWITTERUSERS);
		String[] projection = {"_id", TwitterUsers.COL_LASTUPDATE, TwitterUsers.COL_FLAGS};
		
		Long userId = null;
		
		ContentValues cv = getUserContentValues(user);
		
		Cursor c = getContentResolver().query(uri, projection, TwitterUsers.COL_ID+"="+user.getId(), null, null);
		if(c.getCount() == 0){ // we don't have the local user in the DB yet!
			// we flag new users for updating their profile image
			cv.put(TwitterUsers.COL_FLAGS, TwitterUsers.FLAG_TO_UPDATEIMAGE); 
			Uri insertUri = Uri.parse("content://" + TwitterUsers.TWITTERUSERS_AUTHORITY + "/" + TwitterUsers.TWITTERUSERS);
			Uri resultUri = getContentResolver().insert(insertUri, cv);
			userId = new Long(resultUri.getLastPathSegment());

		} else {
			c.moveToFirst();
			
			// get the profile image?
			if(System.currentTimeMillis() - c.getLong(c.getColumnIndex(TwitterUsers.COL_LASTUPDATE)) > Constants.USERS_MIN_SYNCH){
				cv.put(TwitterUsers.COL_FLAGS, TwitterUsers.FLAG_TO_UPDATEIMAGE|c.getInt(c.getColumnIndex(TwitterUsers.COL_FLAGS)));
			}

			Uri updateUri = Uri.parse("content://" + TwitterUsers.TWITTERUSERS_AUTHORITY + "/" + TwitterUsers.TWITTERUSERS + "/" + c.getInt(c.getColumnIndex("_id")));
			getContentResolver().update(updateUri, cv, null, null);
			userId = c.getLong(c.getColumnIndex("_id"));

		}
		c.close();
		

		return userId;
		
	}

	/**
	 * Creates content values for a tweet from Twitter
	 * @param tweet
	 * @return
	 */
	private ContentValues getTweetContentValues(Twitter.Status tweet, int buffer) {
		ContentValues cv = new ContentValues();
		cv.put(Tweets.COL_TEXT, tweet.getText());
		cv.put(Tweets.COL_CREATED, tweet.getCreatedAt().getTime());
		cv.put(Tweets.COL_SOURCE, tweet.source);
		cv.put(Tweets.COL_TID, tweet.getId().longValue());
		cv.put(Tweets.COL_FAVORITED, tweet.isFavorite());
		
		// TODO: How do we know if we have retweeted the tweet?
		cv.put(Tweets.COL_RETWEETED, 0);
		cv.put(Tweets.COL_RETWEETCOUNT, tweet.retweetCount);
		if(tweet.inReplyToStatusId != null){
			cv.put(Tweets.COL_REPLYTO, tweet.inReplyToStatusId.longValue());
		}
		cv.put(Tweets.COL_USER, tweet.getUser().getId());
		//cv.put(Tweets.COL_FLAGS, 0);
		cv.put(Tweets.COL_BUFFER, buffer);
		
		// TODO: Location (enter coordinates of tweet)
		Log.e(TAG, "Location: "+ tweet.getLocation());
		
		return cv;
	}
	
	/**
	 * Creates content values for a DM from Twitter
	 * @param dm
	 * @param buffer
	 * @return
	 */

	private ContentValues getMessageContentValues(Message dm, int buffer) {
		ContentValues cv = new ContentValues();
		cv.put(DirectMessages.COL_TEXT, dm.getText());
		cv.put(DirectMessages.COL_CREATED, dm.getCreatedAt().getTime());
		cv.put(DirectMessages.COL_DMID, dm.getId());
		
		cv.put(DirectMessages.COL_SENDER, dm.getSender().getId());
		cv.put(DirectMessages.COL_RECEIVER, dm.getRecipient().getId());
		cv.put(DirectMessages.COL_RECEIVER_SCREENNAME, dm.getRecipient().getScreenName());
		cv.put(Tweets.COL_BUFFER, buffer);
		
		return cv;
	}

	
	/**
	 * Creates content values for a user from Twitter
	 * @param user
	 * @return
	 */
	private ContentValues getUserContentValues(User user) {
		ContentValues userContentValues = new ContentValues();
		
		if(user!=null){
			userContentValues.put(TwitterUsers.COL_ID, user.id);
			userContentValues.put(TwitterUsers.COL_SCREENNAME, user.screenName);
			userContentValues.put(TwitterUsers.COL_NAME, user.name);
			userContentValues.put(TwitterUsers.COL_DESCRIPTION, user.description);
			userContentValues.put(TwitterUsers.COL_LOCATION, user.location);
			userContentValues.put(TwitterUsers.COL_FAVORITES, user.favoritesCount);
			userContentValues.put(TwitterUsers.COL_FRIENDS, user.friendsCount);
			userContentValues.put(TwitterUsers.COL_FOLLOWERS, user.followersCount);
			userContentValues.put(TwitterUsers.COL_LISTED, user.listedCount);
			userContentValues.put(TwitterUsers.COL_TIMEZONE, user.timezone);
			userContentValues.put(TwitterUsers.COL_STATUSES, user.statusesCount);
			userContentValues.put(TwitterUsers.COL_VERIFIED, user.verified);
			userContentValues.put(TwitterUsers.COL_PROTECTED, user.protectedUser);
			//userContentValues.put(TwitterUsers.COL_ISFOLLOWER, user.isFollowingYou()?1:0);
			userContentValues.put(TwitterUsers.COL_ISFRIEND, user.isFollowedByYou()?1:0);
			userContentValues.put(TwitterUsers.COL_IMAGEURL, user.getProfileImageUrl().toString());
		}
		return userContentValues;
	}
	
	/**
	 * Logs in with Twitter and writes the local user into the DB.
	 * @author thossmann
	 *
	 */
	private class VerifyCredentialsTask extends AsyncTask<Integer, Void, Twitter.User> {
		
		int attempts;
		Exception ex;
		
		@Override
	     protected Twitter.User doInBackground(Integer... params) {
			Log.i(TAG, "AsynchTask: VerifyCredentialsTask");
			attempts = params[0];
			Twitter.User user = null;
			
			try {
				TwitterAccount twitterAcc = new TwitterAccount(twitter);
				user = twitterAcc.verifyCredentials();
				
			} catch (Exception ex) {
				// save the exception to handle it in onPostExecute
				this.ex = ex;	
			}
	         
	        return user;
	     }

		@Override
	     protected void onPostExecute(Twitter.User result) {
			
			// error handling
			if(ex != null){
				// user not authorized!
				if(ex instanceof TwitterException.E401){
					// tell the user that the login was not successful
					Intent timelineIntent = new Intent(LoginActivity.LOGIN_RESULT_INTENT);
					timelineIntent.putExtra(LoginActivity.LOGIN_RESULT, LoginActivity.LOGIN_FAILURE);
					sendBroadcast(timelineIntent);
				} else {
					if(attempts>0){
						(new VerifyCredentialsTask()).execute(--attempts);
					} else {
						// tell the user that the login was not successful
						Intent timelineIntent = new Intent(LoginActivity.LOGIN_RESULT_INTENT);
						timelineIntent.putExtra(LoginActivity.LOGIN_RESULT, LoginActivity.LOGIN_FAILURE);
						sendBroadcast(timelineIntent);

					}
				}
			}
			
			// this should not happen!
			if(result==null) {
				// if we still have more attempts, we start a new thread
				if(attempts>0){
					(new VerifyCredentialsTask()).execute(--attempts);
				} else {
					// tell the user that the login was not successful
					Intent timelineIntent = new Intent(LoginActivity.LOGIN_RESULT_INTENT);
					timelineIntent.putExtra(LoginActivity.LOGIN_RESULT, LoginActivity.LOGIN_FAILURE);
					sendBroadcast(timelineIntent);

				}
				return;
			} else {
				// update user in DB
				updateUser(result);
				// store user Id and screenname in shared prefs
				LoginActivity.setTwitterId(Long.toString(result.getId()), getBaseContext());
				LoginActivity.setTwitterScreenname(result.getScreenName(), getBaseContext());
							
				Intent timelineIntent = new Intent(LoginActivity.LOGIN_RESULT_INTENT);
				timelineIntent.putExtra(LoginActivity.LOGIN_RESULT, LoginActivity.LOGIN_SUCCESS);
				sendBroadcast(timelineIntent);

				
			}
	     }
	}
	
	/**
	 * Updates the mentions
	 * @author thossmann
	 *
	 */
	private class UpdateMentionsTask extends AsyncTask<Void, Void, List<Twitter.Status>> {
		
		Exception ex;
		
		@Override
	     protected List<Twitter.Status> doInBackground(Void... params) {
			Log.i(TAG, "AsynchTask: UpdateMentionsTask");
			ShowTweetListActivity.setLoading(true);
			
			List<Twitter.Status> mentions = null;

			twitter.setCount(Constants.NR_MENTIONS);			
			twitter.setSinceId(getMentionsSinceId(getBaseContext()));

			try {
				mentions = twitter.getReplies();
			} catch (Exception ex) {					
				this.ex = ex; // save the exception for later handling
			}
	         
	        return mentions;
	     }

		@Override
	     protected void onPostExecute(List<Twitter.Status> result) {
			
			ShowTweetListActivity.setLoading(false);
			
			// error handling
			if(ex != null){
				if(ex instanceof TwitterException.RateLimit){
					Toast.makeText(getBaseContext(), "Rate limit. Please try again later!", Toast.LENGTH_SHORT).show();
				} else {
					Toast.makeText(getBaseContext(), "Something went wrong when loading your mentions. Please try again later!", Toast.LENGTH_SHORT).show();
				}
				return;
			}

			// no mentions to insert
			if(result==null) return;
			
			if(!result.isEmpty()){
				BigInteger lastId = null;
				
				for (Twitter.Status tweet: result) {
					if(lastId == null)
						lastId = tweet.getId();
					
					updateUser(tweet.getUser());
					updateTweet(tweet, Tweets.BUFFER_MENTIONS);
					
				}
				
				// trigger the user synch (for updating the profile images)
				synchTransactionalUsers();
				
				// save the id of the last tweet for future timeline synchs
				setMentionsSinceId(lastId, getBaseContext());
			}
			
			// save the timestamp of the last update
			setLastMentionsUpdate(new Date(), getBaseContext());
	     }

	 }
	/**
	 * Updates the favorites
	 * @author thossmann
	 *
	 */
	private class UpdateFavoritesTask extends AsyncTask<Void, Void, List<Twitter.Status>> {
		
		Exception ex;
		
		@Override
	     protected List<Twitter.Status> doInBackground(Void... params) {
			Log.i(TAG, "AsynchTask: UpdateFavoritesTask");
			ShowTweetListActivity.setLoading(true);
			List<Twitter.Status> favorites = null;

			twitter.setCount(Constants.NR_FAVORITES);			
			twitter.setSinceId(getFavoritesSinceId(getBaseContext()));

			
			try {
				favorites = twitter.getFavorites();
			} catch (Exception ex) {	
				this.ex = ex;
			}
	         
	        return favorites;
	     }

		@Override
	     protected void onPostExecute(List<Twitter.Status> result) {
			ShowTweetListActivity.setLoading(false);
			
			// error handling
			if(ex != null){
				if(ex instanceof TwitterException.RateLimit){
					Toast.makeText(getBaseContext(), "Rate limit. Please try again later!", Toast.LENGTH_SHORT).show();
				} else {
					Toast.makeText(getBaseContext(), "Something went wrong when loading your favorites. Please try again later!", Toast.LENGTH_SHORT).show();
				}
				return;
			}

			// no favorites to insert
			if(result==null) return;
			
			if(!result.isEmpty()){
				BigInteger lastId = null;
				
				for (Twitter.Status tweet: result) {
					if(lastId == null)
						lastId = tweet.getId();
					
					updateUser(tweet.getUser());
					updateTweet(tweet, Tweets.BUFFER_FAVORITES);
					
				}
				
				// trigger the user synch (for updating the profile images)
				synchTransactionalUsers();
				
				// save the id of the last tweet for future timeline synchs
				setFavoritesSinceId(lastId, getBaseContext());
			}
			
			// save the timestamp of the last update
			setLastFavoritesUpdate(new Date(), getBaseContext());
	     }

	 }
	/**
	 * Loads the timeline
	 * @author thossmann
	 *
	 */
	private class UpdateTimelineTask extends AsyncTask<Void, Void, List<Twitter.Status>> {
		
		Exception ex;
		
		@Override
	     protected List<Twitter.Status> doInBackground(Void... params) {
			Log.i(TAG, "AsynchTask: UpdateTimelineTask");
			ShowTweetListActivity.setLoading(true);
			
			List<Twitter.Status> timeline = null;
			twitter.setCount(Constants.NR_TWEETS);
			twitter.setSinceId(getTimelineSinceId(getBaseContext()));

			
			try {
				timeline = twitter.getHomeTimeline();
			} catch (Exception ex) {
				this.ex = ex;
			}
	         
	        return timeline;
	     }

		@Override
	     protected void onPostExecute(List<Twitter.Status> result) {
			ShowTweetListActivity.setLoading(false);
			
			// error handling
			if(ex != null){
				if(ex instanceof TwitterException.RateLimit){
					Toast.makeText(getBaseContext(), "Rate limit. Please try again later!", Toast.LENGTH_SHORT).show();
				} else {
					Toast.makeText(getBaseContext(), "Something went wrong when loading your timeline. Please try again later!", Toast.LENGTH_SHORT).show();
				}
				return;
			}

			// no tweets to insert
			if(result==null) return;
			
			if(!result.isEmpty()){
				BigInteger lastId = null;
				
				for (Twitter.Status tweet: result) {
					if(lastId == null)
						lastId = tweet.getId();
					
					updateUser(tweet.getUser());
					updateTweet(tweet, Tweets.BUFFER_TIMELINE);
					
				}
				
				// trigger the user synch (for updating the profile images)
				synchTransactionalUsers();
				
				// save the id of the last tweet for future timeline synchs
				setTimelineSinceId(lastId, getBaseContext());
			}
			
			// save the timestamp of the last update
			setLastTimelineUpdate(new Date(), getBaseContext());
	     }

	 }
	
	/**
	 * Updates the list of friends
	 * @author thossmann
	 *
	 */
	private class UpdateFriendsTask extends AsyncTask<Void, Void, List<Number>> {
		
		Exception ex;
		
		@Override
	     protected List<Number> doInBackground(Void... params) {
			Log.i(TAG, "AsynchTask: UpdateFriendsTask");
			ShowUserListActivity.setLoading(true);
			
			List<Number> friendsList = null;
			
			try {
				friendsList = twitter.getFriendIDs();

			} catch (Exception ex) {
				this.ex = ex;
			}
	         
	        return friendsList;
	     }

		@Override
	     protected void onPostExecute(List<Number> result) {
			ShowTweetListActivity.setLoading(false);
			
			// error handling
			if(ex != null){
				if(ex instanceof TwitterException.RateLimit){
					Toast.makeText(getBaseContext(), "Rate limit. Please try again later!", Toast.LENGTH_SHORT).show();
				} else {
					Toast.makeText(getBaseContext(), "Something went wrong when loading your friends. Please try again later!", Toast.LENGTH_SHORT).show();
				}
				return;
			}

			// no friends to insert
			if(result==null) return;

			
			// this is the list of user IDs we will request updates for
			List<Long> toLookup = new ArrayList<Long>();
			
			if(!result.isEmpty()){
				for (Number userId: result) {
					
					ContentValues cv = new ContentValues();
					// all we know is the user id and that we follow them
					cv.put(TwitterUsers.COL_ID, userId.longValue());
					cv.put(TwitterUsers.COL_ISFRIEND, 1);

					// do we already have the user?
					Uri uri = Uri.parse("content://" + TwitterUsers.TWITTERUSERS_AUTHORITY + "/" + TwitterUsers.TWITTERUSERS);
					String[] projection = {"_id", TwitterUsers.COL_LASTUPDATE};
					Cursor c = getContentResolver().query(uri, projection, TwitterUsers.COL_ID+"="+userId, null, null);
					if(c.getCount() == 0){ // we don't have the local user in the DB yet!
						Uri insertUri = Uri.parse("content://" + TwitterUsers.TWITTERUSERS_AUTHORITY + "/" + TwitterUsers.TWITTERUSERS);
						getContentResolver().insert(insertUri, cv);
						
						// do we need to update the user?
						if(toLookup.size()< 100){
							toLookup.add((Long) userId);
						}

					} else {
						c.moveToFirst();
						Uri updateUri = Uri.parse("content://" + TwitterUsers.TWITTERUSERS_AUTHORITY + "/" + TwitterUsers.TWITTERUSERS + "/" + c.getInt(c.getColumnIndex("_id")));
						getContentResolver().update(updateUri, cv, null, null);

						// do we need to update the user?
						if(toLookup.size()< 100){
							if(c.isNull(c.getColumnIndex(TwitterUsers.COL_LASTUPDATE)) || (System.currentTimeMillis() - c.getLong(c.getColumnIndex(TwitterUsers.COL_LASTUPDATE)) > Constants.USERS_MIN_SYNCH)){
								toLookup.add((Long) userId);
							}
						}
					}
					c.close();
				}
				
			}

			// save the timestamp of the last update
			setLastFriendsUpdate(new Date(), getBaseContext());
			
			// if we have users to lookup, we do it now
			if(!toLookup.isEmpty()){
				new UpdateUserListTask().execute(toLookup);
			}
	     }

	 }
	
	/**
	 * Updates the list of followers
	 * @author thossmann
	 *
	 */
	private class UpdateFollowersTask extends AsyncTask<Void, Void, List<Number>> {
		
		Exception ex;
		
		@Override
	     protected List<Number> doInBackground(Void... params) {
			Log.i(TAG, "AsynchTask: UpdateFollowersTask");
			ShowUserListActivity.setLoading(true);
			
			List<Number> followersList = null;
			
			try {
				followersList = twitter.getFollowerIDs();

			} catch (Exception ex) {
				this.ex = ex;
			}
	         
	        return followersList;
	     }

		@Override
	     protected void onPostExecute(List<Number> result) {
			ShowTweetListActivity.setLoading(false);
			
			// error handling
			if(ex != null){
				if(ex instanceof TwitterException.RateLimit){
					Toast.makeText(getBaseContext(), "Rate limit. Please try again later!", Toast.LENGTH_SHORT).show();
				} else {
					Toast.makeText(getBaseContext(), "Something went wrong when loading your followers. Please try again later!", Toast.LENGTH_SHORT).show();
				}
				return;
			}

			// no friends to insert
			if(result==null) return;
			
			// this is the list of user IDs we will request updates for
			List<Long> toLookup = new ArrayList<Long>();
			
			for (Number userId: result) {
				
				ContentValues cv = new ContentValues();
				// all we know is the user id and that they follow us
				cv.put(TwitterUsers.COL_ID, userId.longValue());
				cv.put(TwitterUsers.COL_ISFOLLOWER, 1);

				// do we already have the user?
				Uri uri = Uri.parse("content://" + TwitterUsers.TWITTERUSERS_AUTHORITY + "/" + TwitterUsers.TWITTERUSERS);
				String[] projection = {"_id", TwitterUsers.COL_LASTUPDATE};
				Cursor c = getContentResolver().query(uri, projection, TwitterUsers.COL_ID+"="+userId, null, null);
				if(c.getCount() == 0){ // we don't have the local user in the DB yet!
					Uri insertUri = Uri.parse("content://" + TwitterUsers.TWITTERUSERS_AUTHORITY + "/" + TwitterUsers.TWITTERUSERS);
					getContentResolver().insert(insertUri, cv);
					
					// do we need to update the user?
					if(toLookup.size()< 100){
						toLookup.add((Long) userId);
					}

				} else {
					c.moveToFirst();
					
					Uri updateUri = Uri.parse("content://" + TwitterUsers.TWITTERUSERS_AUTHORITY + "/" + TwitterUsers.TWITTERUSERS + "/" + c.getInt(c.getColumnIndex("_id")));
					getContentResolver().update(updateUri, cv, null, null);

					// do we need to update the user?
					if(toLookup.size()< 100){
						if(c.isNull(c.getColumnIndex(TwitterUsers.COL_LASTUPDATE)) || (System.currentTimeMillis() - c.getLong(c.getColumnIndex(TwitterUsers.COL_LASTUPDATE)) > Constants.USERS_MIN_SYNCH)){
							toLookup.add((Long) userId);
						}
					}

				}
				c.close();
			}

			// save the timestamp of the last update
			setLastFollowerUpdate(new Date(), getBaseContext());
			
			// if we have users to lookup, we do it now
			if(!toLookup.isEmpty()){
				new UpdateUserListTask().execute(toLookup);
			}
	     }

	 }
	
	/**
	 * Post a tweet to twitter
	 * @author thossmann
	 */
	private class UpdateStatusTask extends AsyncTask<Long, Void, Twitter.Status> {
		
		long rowId;
		int flags;
		int buffer;
		
		Exception ex;
		
		@Override
	     protected Twitter.Status doInBackground(Long... rowId) {
			Log.i(TAG, "AsynchTask: UpdateStatusTask");
			ShowTweetListActivity.setLoading(true);
			this.rowId = rowId[0];
			
			Uri queryUri = Uri.parse("content://"+Tweets.TWEET_AUTHORITY+"/"+Tweets.TWEETS+"/"+this.rowId);
			Cursor c = getContentResolver().query(queryUri, null, null, null, null);
			
			if(c.getCount() == 0){
				Log.w(TAG, "UpdateStatusTask: Tweet not found " + this.rowId);
				return null;
			}
			c.moveToFirst();
			flags = c.getInt(c.getColumnIndex(Tweets.COL_FLAGS));
			buffer = c.getInt(c.getColumnIndex(Tweets.COL_BUFFER));

			Twitter.Status tweet = null;
			
			try {
				String text = c.getString(c.getColumnIndex(Tweets.COL_TEXT));
				
				if(!(c.getDouble(c.getColumnIndex(Tweets.COL_LAT))==0 & c.getDouble(c.getColumnIndex(Tweets.COL_LNG))==0)){
					double[] location = {c.getDouble(c.getColumnIndex(Tweets.COL_LAT)),c.getDouble(c.getColumnIndex(Tweets.COL_LNG))}; 
					twitter.setMyLocation(location);
				} else {
					twitter.setMyLocation(null);
				}
				if(c.getColumnIndex(Tweets.COL_REPLYTO)>=0){
					tweet = twitter.updateStatus(text, c.getLong(c.getColumnIndex(Tweets.COL_REPLYTO)));
				} else {
					tweet = twitter.updateStatus(text);
				}
				
			} catch(Exception ex) { 
				this.ex = ex;
			} finally {
				c.close();
			}
	        return tweet;
	     }

		/**
		 * Clear to insert flag and update the tweet with the information from twitter
		 */
		@Override
	     protected void onPostExecute(Twitter.Status result) {
			ShowTweetListActivity.setLoading(false);
			
			// error handling
			if(ex != null){
				if(ex instanceof TwitterException.Repetition){
					Toast.makeText(getBaseContext(), "Tweet already posted!", Toast.LENGTH_SHORT).show();
					return;
				} else {
					Toast.makeText(getBaseContext(), "Something went wrong when posting your tweet. Please try again later!", Toast.LENGTH_SHORT).show();
					return;
				}
			}

			Uri queryUri = Uri.parse("content://"+Tweets.TWEET_AUTHORITY+"/"+Tweets.TWEETS+"/"+this.rowId);
			
			ContentValues cv = getTweetContentValues(result, 0);
			cv.put(Tweets.COL_FLAGS, flags & ~(Tweets.FLAG_TO_INSERT));
			cv.put(Tweets.COL_BUFFER, buffer);
			
			getContentResolver().update(queryUri, cv, null, null);
			
	     }

	 }
	
	/**
	 * Gets user info for a list of users from twitter
	 * @author thossmann
	 */
	private class UpdateUserListTask extends AsyncTask<List<Long>, Void, List<Twitter.User>> {
		
		Exception ex;
		
		@Override
	     protected List<Twitter.User> doInBackground(List<Long>... userIds) {
			Log.i(TAG, "AsynchTask: UpdateUserListTask");
			ShowUserListActivity.setLoading(true);
			
			List<Twitter.User> userList = null;
			try {
				userList = twitter.bulkShowById(userIds[0]);

			} catch (Exception ex) {
				this.ex = ex;
			} 
	        return userList;
	     }

		/**
		 * Clear to update flag and update the user with the information from twitter
		 */
		@Override
	     protected void onPostExecute(List<Twitter.User> result) {
			
			ShowUserListActivity.setLoading(false);
			
			if(result==null || result.isEmpty()){
				return;
			}
			
			ContentValues cv = new ContentValues();
			for (Twitter.User user: result) {
				
				long rowId = 0;
				
				cv= getUserContentValues(user);
				cv.put(TwitterUsers.COL_LASTUPDATE, System.currentTimeMillis());

				
				// get the row of the user in the DB
				Uri uri = Uri.parse("content://" + TwitterUsers.TWITTERUSERS_AUTHORITY + "/" + TwitterUsers.TWITTERUSERS);
				String[] projection = {"_id", TwitterUsers.COL_LASTUPDATE, TwitterUsers.COL_FLAGS};
				
				Cursor c = getContentResolver().query(uri, projection, TwitterUsers.COL_ID+"="+user.id, null, null);
				if(c.getCount() > 0){
					c.moveToFirst();
					rowId = c.getLong(c.getColumnIndex("_id"));
					
					// get the profile image?
					if(System.currentTimeMillis() - c.getLong(c.getColumnIndex(TwitterUsers.COL_LASTUPDATE)) > Constants.USERS_MIN_SYNCH){
						cv.put(TwitterUsers.COL_FLAGS, TwitterUsers.FLAG_TO_UPDATEIMAGE|c.getInt(c.getColumnIndex(TwitterUsers.COL_FLAGS)));
					}
					
					Uri updateUri = Uri.parse("content://"+TwitterUsers.TWITTERUSERS_AUTHORITY+"/"+TwitterUsers.TWITTERUSERS+"/"+rowId);			
					getContentResolver().update(updateUri, cv, null, null);


				} else {
					Uri insertUri = Uri.parse("content://"+TwitterUsers.TWITTERUSERS_AUTHORITY+"/"+TwitterUsers.TWITTERUSERS);			
					getContentResolver().update(insertUri, cv, null, null);
					
				}
				c.close();

			}
			
			// trigger the user synch (for updating the profile images)
			synchTransactionalUsers();
			
			getContentResolver().notifyChange(TwitterUsers.CONTENT_URI, null);
			getContentResolver().notifyChange(Tweets.CONTENT_URI, null);

		
	     }

	 }
	
	/**
	 * Gets user info from twitter
	 * @author thossmann
	 */
	private class UpdateUserTask extends AsyncTask<Long, Void, Twitter.User> {
		
		long rowId;
		int flags;
		
		Exception ex;
		
		@Override
	     protected Twitter.User doInBackground(Long... rowId) {
			Log.i(TAG, "AsynchTask: UpdateUserTask");
			ShowUserListActivity.setLoading(true);
			this.rowId = rowId[0];
			
			Uri queryUri = Uri.parse("content://"+TwitterUsers.TWITTERUSERS_AUTHORITY+"/"+TwitterUsers.TWITTERUSERS+"/"+this.rowId);
			Cursor c = getContentResolver().query(queryUri, null, null, null, null);
			
			if(c.getCount() == 0){
				Log.w(TAG, "UpdateUserTask: User not found " + this.rowId);
				c.close();
				return null;
			}
			c.moveToFirst();
			flags = c.getInt(c.getColumnIndex(TwitterUsers.COL_FLAGS));

			Twitter.User user = null;
			
			try {
				user = twitter.show(c.getLong(c.getColumnIndex(TwitterUsers.COL_ID)));

			} catch (Exception ex) {
				this.ex = ex;
			} finally {
				c.close();
			}
	        return user;
	     }

		/**
		 * Clear to update flag and update the user with the information from twitter
		 */
		@Override
	     protected void onPostExecute(Twitter.User result) {
			ShowUserListActivity.setLoading(false);
			
			// we get null if something went wrong
			ContentValues cv = getUserContentValues(result);
			if(result!=null) {
				cv= getUserContentValues(result);
				cv.put(TwitterUsers.COL_LASTUPDATE, System.currentTimeMillis());
			}
			// we clear the to update flag in any case
			cv.put(TwitterUsers.COL_FLAGS, flags & ~(TwitterUsers.FLAG_TO_UPDATE));
			
			Uri queryUri = Uri.parse("content://"+TwitterUsers.TWITTERUSERS_AUTHORITY+"/"+TwitterUsers.TWITTERUSERS+"/"+this.rowId);			
			getContentResolver().update(queryUri, cv, null, null);
			
			
	     }

	 }
	
	/**
	 * Gets profile image from twitter
	 * @author thossmann
	 */
	private class UpdateProfileImageTask extends AsyncTask<Long, Void, HttpEntity> {
		
		long rowId;
		Exception ex;
		
		@Override
	     protected HttpEntity doInBackground(Long... rowId) {
			Log.i(TAG, "AsynchTask: UpdateProfileImageTask");
			ShowUserListActivity.setLoading(true);
			this.rowId = rowId[0];
			
			Uri queryUri = Uri.parse("content://"+TwitterUsers.TWITTERUSERS_AUTHORITY+"/"+TwitterUsers.TWITTERUSERS+"/"+this.rowId);
			Cursor c = getContentResolver().query(queryUri, null, null, null, null);
			
			if(c.getCount() == 0){
				Log.w(TAG, "UpdateUserTask: User not found " + this.rowId);
				c.close();
				return null;
			}
			c.moveToFirst();
			
			String imageUrl = null;
			// this should not happen
			if(c.isNull(c.getColumnIndex(TwitterUsers.COL_IMAGEURL))){
				c.close();
				ex = new Exception();
				return null;
			} else {
				imageUrl = c.getString(c.getColumnIndex(TwitterUsers.COL_IMAGEURL));
			}
			
			HttpEntity entity = null;
			
			try {

				DefaultHttpClient mHttpClient = new DefaultHttpClient();
				HttpGet mHttpGet = new HttpGet(imageUrl);
				HttpResponse mHttpResponse = mHttpClient.execute(mHttpGet);
				if (mHttpResponse.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
				  entity = mHttpResponse.getEntity();
				}

			} catch (Exception ex) {
				this.ex = ex;
			} finally {
				
				// clear the update image flag
				ContentValues cv = new ContentValues();
				cv.put(TwitterUsers.COL_FLAGS, ~(TwitterUsers.FLAG_TO_UPDATEIMAGE) & c.getInt(c.getColumnIndex(TwitterUsers.COL_FLAGS)));
				getContentResolver().update(queryUri, cv, null, null);
				
				c.close();

			}
	        return entity;
	     }

		/**
		 * Clear to update flag and update the user with the information from twitter
		 */
		@Override
	     protected void onPostExecute(HttpEntity result) {
			// we get null if something went wrong, we just stop
			if(result == null) {
				Log.i(TAG, "error while loading profile image!");
				return;
			}
			
			ContentValues cv = new ContentValues();
			try {
				cv.put(TwitterUsers.COL_PROFILEIMAGE, EntityUtils.toByteArray(result));
			} catch (IOException e) {
				Log.e(TAG, "IOException while getting image from http entity");
				return;
			}
			
			Uri queryUri = Uri.parse("content://"+TwitterUsers.TWITTERUSERS_AUTHORITY+"/"+TwitterUsers.TWITTERUSERS+"/"+this.rowId);			
			getContentResolver().update(queryUri, cv, null, null);
			ShowUserListActivity.setLoading(false);
			
			// here, we have to notify almost everyone
			getContentResolver().notifyChange(TwitterUsers.CONTENT_URI, null);
			getContentResolver().notifyChange(Tweets.CONTENT_URI, null);
			getContentResolver().notifyChange(DirectMessages.CONTENT_URI, null);

			
	     }

	 }
	
	/**
	 * Delete a tweet from twitter and from the content provider
	 * @author thossmann
	 */
	private class DestroyStatusTask extends AsyncTask<Long, Void, Integer> {
		
		long rowId;
		int flags;
		
		Exception ex;
		
		@Override
	     protected Integer doInBackground(Long... rowId) {
			Log.i(TAG, "AsynchTask: DestroyStatusTask");
			ShowTweetListActivity.setLoading(true);
			this.rowId = rowId[0];
			Integer result = null;
			
			Uri queryUri = Uri.parse("content://"+Tweets.TWEET_AUTHORITY+"/"+Tweets.TWEETS+"/"+this.rowId);
			Cursor c = getContentResolver().query(queryUri, null, null, null, null);
			
			// making sure the tweet was found in the content provider
			if(c.getCount() == 0){
				Log.w(TAG, "DestroyStatusTask: Tweet not found " + this.rowId);
				ex = new Exception();
				c.close();
				return null;
			}
			c.moveToFirst();
			
			// checking if we really have an official Twitter ID
			if(c.getColumnIndex(Tweets.COL_TID)<0 | c.getLong(c.getColumnIndex(Tweets.COL_TID)) == 0){
				Log.i(TAG, "DestroyStatusTask: Tweet has no ID! " + this.rowId);
				ex = new Exception();
				c.close();
				return null;
			}

			flags = c.getInt(c.getColumnIndex(Tweets.COL_FLAGS));
			try {
				twitter.destroyStatus(c.getLong(c.getColumnIndex(Tweets.COL_TID)));
				result = 1;
			} catch (Exception ex) {
				this.ex = ex;
			} finally {
				c.close();
			}

	        return result;
	     }

		/**
		 * If successful, we delete the tweet also locally. If not successful, we keep the tweet and clear the todelete flag
		 */
		@Override
	     protected void onPostExecute(Integer result) {
			ShowTweetListActivity.setLoading(false);
			
			// error handling
			if(ex != null){
				if(ex instanceof TwitterException.Repetition){
					Toast.makeText(getBaseContext(), "Already deleted!", Toast.LENGTH_SHORT).show();
				} else {
					// an exception happended, we notify the user					
					Toast.makeText(getBaseContext(), "Something went wrong while deleting. We will try again later!", Toast.LENGTH_LONG).show();
					return;
				}
			}
			
			if(result == null){
				ContentValues cv = new ContentValues();
				cv.put(Tweets.COL_FLAGS, flags & ~Tweets.FLAG_TO_DELETE);
				Uri updateUri = Uri.parse("content://"+Tweets.TWEET_AUTHORITY+"/"+Tweets.TWEETS+"/"+this.rowId);
				getContentResolver().update(updateUri, cv, null, null);

			} else {
				Uri deleteUri = Uri.parse("content://"+Tweets.TWEET_AUTHORITY+"/"+Tweets.TWEETS+"/"+this.rowId);
				getContentResolver().delete(deleteUri, null, null);
				
				Toast.makeText(getBaseContext(), "Delete successful.", Toast.LENGTH_SHORT).show();
			}
	     }

	 }
	
	/**
	 * Favorite a tweet on twitter and clear the respective flag locally
	 * @author thossmann
	 */
	private class FavoriteStatusTask extends AsyncTask<Long, Void, Integer> {
		
		long rowId;
		int flags;
		int buffer;
		
		Exception ex;
		
		@SuppressWarnings("deprecation")
		@Override
	     protected Integer doInBackground(Long... rowId) {
			Log.i(TAG, "AsynchTask: FavoriteStatusTask");
			ShowTweetListActivity.setLoading(true);
			this.rowId = rowId[0];
			Integer result = null;
			
			Uri queryUri = Uri.parse("content://"+Tweets.TWEET_AUTHORITY+"/"+Tweets.TWEETS+"/"+this.rowId);
			Cursor c = getContentResolver().query(queryUri, null, null, null, null);
			
			// making sure the Tweet was found in the content provider
			if(c.getCount() == 0){
				Log.w(TAG, "FavoriteStatusTask: Tweet not found " + this.rowId);
				return null;
			}
			c.moveToFirst();

			// making sure we have an official Tweet ID from Twitter
			if(c.getColumnIndex(Tweets.COL_TID)<0 || c.getLong(c.getColumnIndex(Tweets.COL_TID)) == 0){
				Log.w(TAG, "FavoriteStatusTask: Tweet has no ID! " + this.rowId);
				c.close();
				return null;
			}

			// save the flags for clearing the to favorite flag later
			flags = c.getInt(c.getColumnIndex(Tweets.COL_FLAGS));
			buffer = c.getInt(c.getColumnIndex(Tweets.COL_BUFFER));
			
			try {
				twitter.setFavorite(new Twitter.Status(null, null, c.getLong(c.getColumnIndex(Tweets.COL_TID)), null), true);
				result = 1;
			} catch (Exception ex){
				this.ex = ex;
			} finally {
				c.close();
			}

	        return result;
	     }

		/**
		 * After favoriting Twitter, we clear the to favorite flag locally
		 */
		@Override
	     protected void onPostExecute(Integer result) {
			
			ShowTweetListActivity.setLoading(false);
			
			// error handling
			if(ex != null){
				if(ex instanceof TwitterException.Repetition){
					Toast.makeText(getBaseContext(), "Already not a favorite!", Toast.LENGTH_SHORT).show();
				} else {
					// an exception happended, we notify the user					
					Toast.makeText(getBaseContext(), "Something went wrong while unfavoriting. We will try again later!", Toast.LENGTH_LONG).show();
					return;
				}
			}
			
			ContentValues cv = new ContentValues();
			cv.put(Tweets.COL_FLAGS, flags & ~Tweets.FLAG_TO_FAVORITE);
			cv.put(Tweets.COL_BUFFER, buffer | Tweets.BUFFER_FAVORITES);
			
			// we get null if there was a problem with favoriting (already a favorite, etc.).
			if(result!=null) {
				cv.put(Tweets.COL_FAVORITED, 1);
			}
			
			Uri updateUri = Uri.parse("content://"+Tweets.TWEET_AUTHORITY+"/"+Tweets.TWEETS+"/"+this.rowId);
			getContentResolver().update(updateUri, cv, null, null);
			
			Toast.makeText(getBaseContext(), "Favorite successful.", Toast.LENGTH_SHORT).show();
	     }

	 }
	
	/**
	 * Unfavorite a tweet on twitter and clear the respective flag locally
	 * @author thossmann
	 */
	private class UnfavoriteStatusTask extends AsyncTask<Long, Void, Integer> {
		
		long rowId;
		int flags;
		int buffer;
		
		Exception ex;
		
		@SuppressWarnings("deprecation")
		@Override
	     protected Integer doInBackground(Long... rowId) {
			Log.i(TAG, "AsynchTask: UnfavoriteStatusTask");
			ShowTweetListActivity.setLoading(true);
			this.rowId = rowId[0];
			Integer result = null;
			
			Uri queryUri = Uri.parse("content://"+Tweets.TWEET_AUTHORITY+"/"+Tweets.TWEETS+"/"+this.rowId);
			Cursor c = getContentResolver().query(queryUri, null, null, null, null);
			
			// making sure the Tweet was found in the content provider
			if(c.getCount() == 0){
				Log.w(TAG, "UnfavoriteStatusTask: Tweet not found " + this.rowId);
				c.close();
				ex = new Exception();
				return null;
			}
			c.moveToFirst();

			// making sure we have an official Tweet ID from Twitter
			if(c.getColumnIndex(Tweets.COL_TID)<0 | c.getLong(c.getColumnIndex(Tweets.COL_TID)) == 0){
				Log.w(TAG, "UnavoriteStatusTask: Tweet has no ID! " + this.rowId);
				c.close();
				ex = new Exception();
				return null;
			}

			// save the flags for clearing the to favorite flag later
			flags = c.getInt(c.getColumnIndex(Tweets.COL_FLAGS));
			buffer = c.getInt(c.getColumnIndex(Tweets.COL_BUFFER));
			
			try {
				twitter.setFavorite(new Twitter.Status(null, null, c.getLong(c.getColumnIndex(Tweets.COL_TID)), null), false);
				result = 1;
			} catch (Exception ex){
				this.ex = ex;
			} finally {
				c.close();
			}
	        return result;
	     }

		/**
		 * After unfavoriting from Twitter, we clear the to unfavorite flag locally
		 */
		@Override
	     protected void onPostExecute(Integer result) {
			ShowTweetListActivity.setLoading(false);
			
			// error handling
			if(ex != null){
				if(ex instanceof TwitterException.Repetition){
					Toast.makeText(getBaseContext(), "Already not a favorite!", Toast.LENGTH_SHORT).show();
				} else {
					// an exception happended, we notify the user					
					Toast.makeText(getBaseContext(), "Something went wrong while unfavoriting. We will try again later!", Toast.LENGTH_LONG).show();
					return;
				}
			}
			
			ContentValues cv = new ContentValues();
			cv.put(Tweets.COL_FLAGS, flags & ~Tweets.FLAG_TO_UNFAVORITE);
			cv.put(Tweets.COL_BUFFER, buffer & ~Tweets.BUFFER_FAVORITES);

			if(result!=null) {
				cv.put(Tweets.COL_FAVORITED, 0);
			}
			
			Uri updateUri = Uri.parse("content://"+Tweets.TWEET_AUTHORITY+"/"+Tweets.TWEETS+"/"+this.rowId);
			getContentResolver().update(updateUri, cv, null, null);
			
			Toast.makeText(getBaseContext(), "Unfavorite successful.", Toast.LENGTH_SHORT).show();
	     }

	 }
	
	/**
	 * Retweet a tweet on twitter and clear the respective flag locally
	 * @author thossmann
	 */
	private class RetweetStatusTask extends AsyncTask<Long, Void, Integer> {
		
		long rowId;
		int flags;
		int buffer;
		
		Exception ex;
		
		@SuppressWarnings("deprecation")
		@Override
	     protected Integer doInBackground(Long... rowId) {
			Log.i(TAG, "AsynchTask: RetweetStatusTask");
			
			ShowTweetListActivity.setLoading(true);
			this.rowId = rowId[0];
			
			Uri queryUri = Uri.parse("content://"+Tweets.TWEET_AUTHORITY+"/"+Tweets.TWEETS+"/"+this.rowId);
			Cursor c = getContentResolver().query(queryUri, null, null, null, null);
			
			// making sure the Tweet was found in the content provider
			if(c.getCount() == 0){
				Log.w(TAG, "RetweetStatusTask: Tweet not found " + this.rowId);
				c.close();
				ex = new Exception();
				return null;
			}
			c.moveToFirst();

			// making sure we have an official Tweet ID from Twitter
			if(c.getColumnIndex(Tweets.COL_TID)<0 | c.getLong(c.getColumnIndex(Tweets.COL_TID)) == 0){
				Log.w(TAG, "RetweetStatusTask: Tweet has no ID! " + this.rowId);
				c.close();
				ex = new Exception();
				return null;
			}

			// save the flags for clearing the to favorite flag later
			flags = c.getInt(c.getColumnIndex(Tweets.COL_FLAGS));
			buffer = c.getInt(c.getColumnIndex(Tweets.COL_BUFFER));
			
			try {
				twitter.retweet(new Twitter.Status(null, null, c.getLong(c.getColumnIndex(Tweets.COL_TID)), null));
			} catch (Exception ex) {
				this.ex = ex;
			} finally {
				c.close();
			}
	        return 1;
	     }

		/**
		 * After retweeting, we clear the to to retweet flag locally
		 */
		@Override
	     protected void onPostExecute(Integer result) {
			ShowTweetListActivity.setLoading(false);
			
			// error handling
			if(ex != null){
				if(ex instanceof TwitterException.Repetition){
					Toast.makeText(getBaseContext(), "Already retweeted!", Toast.LENGTH_SHORT).show();
				} else {
					// an exception happended, we notify the user					
					Toast.makeText(getBaseContext(), "Something went wrong while retweeting. We will try again later!", Toast.LENGTH_LONG).show();
					return;
				}
			}

			ContentValues cv = new ContentValues();
			cv.put(Tweets.COL_FLAGS, flags & ~Tweets.FLAG_TO_RETWEET);
			cv.put(Tweets.COL_BUFFER, buffer);

			if(result!=null) {
				cv.put(Tweets.COL_RETWEETED, 1);
			}
			
			Uri updateUri = Uri.parse("content://"+Tweets.TWEET_AUTHORITY+"/"+Tweets.TWEETS+"/"+this.rowId);
			getContentResolver().update(updateUri, cv, null, null);
			
			Toast.makeText(getBaseContext(), "Retweet successful.", Toast.LENGTH_LONG).show();
	     }

	 }
	
	/**
	 * Send a follow request to Twitter
	 * @author thossmann
	 */
	private class FollowUserTask extends AsyncTask<Long, Void, Twitter.User> {
		
		long rowId;
		int flags;
		
		Exception ex;
		
		@Override
	     protected Twitter.User doInBackground(Long... rowId) {
			Log.i(TAG, "AsynchTask: FollowUserTask");
			
			ShowUserListActivity.setLoading(true);
			this.rowId = rowId[0];
			
			Uri queryUri = Uri.parse("content://"+TwitterUsers.TWITTERUSERS_AUTHORITY+"/"+TwitterUsers.TWITTERUSERS+"/"+this.rowId);
			Cursor c = getContentResolver().query(queryUri, null, null, null, null);
			
			if(c.getCount() == 0){
				Log.w(TAG, "FollowUserTask: User not found " + this.rowId);
				c.close();
				return null;
			}
			c.moveToFirst();
			flags = c.getInt(c.getColumnIndex(TwitterUsers.COL_FLAGS));

			Twitter.User user = null;
			
			try {
				user = twitter.follow(c.getString(c.getColumnIndex(TwitterUsers.COL_SCREENNAME)));
			} catch (Exception ex) {
				this.ex = ex;
			} finally {
				c.close();
			}
	        return user;
	     }

		/**
		 * Clear to follow flag and update the user with the information from twitter
		 */
		@Override
	     protected void onPostExecute(Twitter.User result) {
			ShowUserListActivity.setLoading(false);
			// error handling
			if(ex != null){
				// an exception happended, we notify the user					
				Toast.makeText(getBaseContext(), "Something went wrong while sending the follow request. We will try again later!", Toast.LENGTH_LONG).show();
				return;
			}

			// we get null if: the user does not exist or is protected
			// in any case we clear the to follow flag
			ContentValues cv = getUserContentValues(result);
			cv.put(TwitterUsers.COL_FLAGS, (flags & ~TwitterUsers.FLAG_TO_FOLLOW));
			// we get a user if the follow was successful
			// in that case we also mark the user as followed in the DB
			if(result!=null) {
				cv.put(TwitterUsers.COL_ISFRIEND, 1);
			}

			Uri queryUri = Uri.parse("content://"+TwitterUsers.TWITTERUSERS_AUTHORITY+"/"+TwitterUsers.TWITTERUSERS+"/"+this.rowId);
						
			getContentResolver().update(queryUri, cv, null, null);
			
			Toast.makeText(getBaseContext(), "Follow request sent.", Toast.LENGTH_LONG).show();
	     }

	 }
	
	/**
	 * Unfollow a user on Twitter
	 * @author thossmann
	 */
	private class UnfollowUserTask extends AsyncTask<Long, Void, Twitter.User> {
		
		long rowId;
		int flags;
		
		Exception ex;
		
		@Override
	     protected Twitter.User doInBackground(Long... rowId) {
			
			Log.i(TAG, "AsynchTask: UnfollowUserTask");
			
			ShowUserListActivity.setLoading(true);
			this.rowId = rowId[0];
			
			Uri queryUri = Uri.parse("content://"+TwitterUsers.TWITTERUSERS_AUTHORITY+"/"+TwitterUsers.TWITTERUSERS+"/"+this.rowId);
			Cursor c = getContentResolver().query(queryUri, null, null, null, null);
			
			if(c.getCount() == 0){
				Log.w(TAG, "UnfollowUserTask: User not found " + this.rowId);
				ex = new Exception();
				return null;
			}
			c.moveToFirst();
			flags = c.getInt(c.getColumnIndex(TwitterUsers.COL_FLAGS));

			Twitter.User user = null;
			
			try {
				user = twitter.stopFollowing(c.getString(c.getColumnIndex(TwitterUsers.COL_SCREENNAME)));
			} catch (Exception ex) {
				this.ex = ex;
			} finally {
				c.close();
			}
	        return user;
	     }

		/**
		 * Clear to unfollow flag and update the user with the information from twitter
		 */
		@Override
	     protected void onPostExecute(Twitter.User result) {
			ShowUserListActivity.setLoading(false);
			// error handling
			if(ex != null){
				// an exception happended, we notify the user					
				Toast.makeText(getBaseContext(), "Something went wrong while sending the unfollow request. We will try again later!", Toast.LENGTH_LONG).show();
				return;
			}
			
			// we get null if we did not follow the user
			// in any case we clear the to follow flag
			ContentValues cv = getUserContentValues(result);
			cv.put(TwitterUsers.COL_FLAGS, (flags & ~TwitterUsers.FLAG_TO_UNFOLLOW));
			// we get a user if the follow was successful
			// in that case we remove the follow in the DB
			if(result!=null) {
				cv.put(TwitterUsers.COL_ISFRIEND, 0);
			}
			
			Uri queryUri = Uri.parse("content://"+TwitterUsers.TWITTERUSERS_AUTHORITY+"/"+TwitterUsers.TWITTERUSERS+"/"+this.rowId);
						
			getContentResolver().update(queryUri, cv, null, null);
			
			Toast.makeText(getBaseContext(), "Unfollowed user.", Toast.LENGTH_LONG).show();
			
	     }

	 }
	
	/**
	 * Updates the incoming direct messages
	 * @author thossmann
	 *
	 */
	private class UpdateDMsInTask extends AsyncTask<Integer, Void, List<Twitter.Message>> {
		
		int attempts;
		Exception ex;
		
		@Override
	     protected List<Twitter.Message> doInBackground(Integer... params) {
			Log.i(TAG, "AsynchTask: UpdateDMsInTask");
			
			ShowDMUsersListActivity.setLoading(true);
			attempts = params[0];
			
			List<Twitter.Message> dms = null;

			twitter.setCount(Constants.NR_DMS);
			twitter.setSinceId(getDMsInSinceId(getBaseContext()));

			
			try {
				dms = twitter.getDirectMessages();
			} catch (Exception ex) {					
				// save the expcetion for handling it in on post execute
				this.ex = ex;
			}
	         
	        return dms;
	     }

		@Override
	     protected void onPostExecute(List<Twitter.Message> result) {
			ShowDMUsersListActivity.setLoading(false);
			
			// error handling
			if(ex != null){
				// an exception happended, we try again or notify the user
				if(ex instanceof TwitterException.RateLimit){
					Toast.makeText(getBaseContext(), "Rate limit. Please try again later!", Toast.LENGTH_LONG).show();
					return;
				} else {
					if(attempts>0) {
						Log.w(TAG, "Exception, attempt " + attempts);
						(new UpdateDMsOutTask()).execute(--attempts);
						return;
					} else {
						Toast.makeText(getBaseContext(), "Something went wrong while loading your direct messages. Please try again later!", Toast.LENGTH_LONG).show();
						return;
					}
				}
			}
			
			if(result==null) return;
			
			if(!result.isEmpty()){
				Long lastId = null;
				
				for (Twitter.Message dm: result) {
					if(lastId == null){
						lastId = dm.getId();
						// save the id of the last DM (comes first from twitter) for future synchs
						setDMsInSinceId(new BigInteger(lastId.toString()), getBaseContext());
					}
					
					updateUser(dm.getSender());
					updateMessage(dm, DirectMessages.BUFFER_MESSAGES);
					Log.e(TAG, "Message: "+dm.getText());
					
				}
				
			}
			
			// trigger the user synch (for updating the profile images)
			synchTransactionalUsers();
			
			// save the timestamp of the last update
			setLastDMsInUpdate(new Date(), getBaseContext());
			
	     }

	 }

	/**
	 * Updates the outgoing direct messages
	 * @author thossmann
	 *
	 */
	private class UpdateDMsOutTask extends AsyncTask<Integer, Void, List<Twitter.Message>> {
		int attempts;
		Exception ex;
		@Override
	     protected List<Twitter.Message> doInBackground(Integer... params) {
						
			Log.i(TAG, "AsynchTask: UpdateDMsOutTask");
			ShowDMUsersListActivity.setLoading(true);
			
			attempts = params[0];
			
			List<Twitter.Message> dms = null;
			
			twitter.setCount(Constants.NR_DMS);
			twitter.setSinceId(getDMsOutSinceId(getBaseContext()));
			
			try {
				dms = twitter.getDirectMessagesSent();
			} catch (Exception ex) {					
				// save the expcetion for handling it in on post execute
				this.ex = ex;
			}
	         
	        return dms;
	     }

		@Override
	     protected void onPostExecute(List<Twitter.Message> result) {
			ShowDMUsersListActivity.setLoading(false);
			
			// error handling
			if(ex != null){
				// an exception happended, we try again or notify the user
				if(ex instanceof TwitterException.RateLimit){
					Toast.makeText(getBaseContext(), "Rate limit. Please try again later!", Toast.LENGTH_LONG).show();
					return;
				} else {
					if(attempts>0) {
						Log.w(TAG, "Exception, attempt " + attempts);
						(new UpdateDMsOutTask()).execute(--attempts);
						return;
					} else {
						Toast.makeText(getBaseContext(), "Something went wrong while loading your direct messages. Please try again later!", Toast.LENGTH_LONG).show();
						return;
					}
				}
			}
			
			if(result==null) return;
			
			if(!result.isEmpty()){
				Long lastId = null;
				
				for (Twitter.Message dm: result) {
					if(lastId == null){
						lastId = dm.getId();
						// save the id of the last DM (comes first from twitter) for future synchs
						setDMsOutSinceId(new BigInteger(lastId.toString()), getBaseContext());
					}
					
					updateUser(dm.getRecipient());
					updateMessage(dm, DirectMessages.BUFFER_MESSAGES);
					Log.e(TAG, "Message: "+dm.getText());
					
				}
				
			}
			
			// trigger the user synch (for updating the profile images)
			synchTransactionalUsers();
			
			// save the timestamp of the last update
			setLastDMsOutUpdate(new Date(), getBaseContext());
	     }

	 }
	
	/**
	 * Post a DM to twitter
	 * @author thossmann
	 */
	private class SendMessageTask extends AsyncTask<Long, Void, Twitter.Message> {
		
		long rowId;
		int flags;
		int buffer;
		String text;
		String rec;
		
		Exception ex;
		
		@Override
	     protected Twitter.Message doInBackground(Long... rowId) {
			Log.i(TAG, "AsynchTask: SendMessageTask");
			this.rowId = rowId[0];
			
			Uri queryUri = Uri.parse("content://"+DirectMessages.DM_AUTHORITY+"/"+DirectMessages.DMS+"/"+this.rowId);
			Cursor c = getContentResolver().query(queryUri, null, null, null, null);
			
			if(c.getCount() == 0){
				Log.w(TAG, "SendMessageTask: Message not found " + this.rowId);
				return null;
			}
			c.moveToFirst();
			flags = c.getInt(c.getColumnIndex(DirectMessages.COL_FLAGS));
			buffer = c.getInt(c.getColumnIndex(DirectMessages.COL_BUFFER));

			Twitter.Message msg = null;
			
			try {
				text = c.getString(c.getColumnIndex(DirectMessages.COL_TEXT));
				rec = c.getString(c.getColumnIndex(DirectMessages.COL_RECEIVER_SCREENNAME));
				Log.e(TAG, "sending: " + text + " to " + rec);
				msg = twitter.sendMessage(rec, text);
				
			} catch(Exception ex) { 
				this.ex = ex;
			} finally {
				c.close();
			}
	        return msg;
	     }

		/**
		 * Clear to insert flag and update the message with the information from twitter
		 */
		@Override
	     protected void onPostExecute(Twitter.Message result) {
			
			Uri queryUri = Uri.parse("content://"+DirectMessages.DM_AUTHORITY+"/"+DirectMessages.DMS+"/"+this.rowId);
			
			// error handling
			if(ex != null){
				if(ex instanceof TwitterException.Repetition){
					Toast.makeText(getBaseContext(), "Message already posted!", Toast.LENGTH_SHORT).show();
					getContentResolver().delete(queryUri, null, null);
					Log.w(TAG, "Error: "+ex);
					return;
				}  else {
					Toast.makeText(getBaseContext(), "Could not post message! Maybe the recepient is not following you?", Toast.LENGTH_SHORT).show();
					Intent i = new Intent(getBaseContext(), NewDMActivity.class);
					i.putExtra("recipient", rec);
					i.putExtra("text", text);
					i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					getBaseContext().startActivity(i);
					getContentResolver().delete(queryUri, null, null);
					Log.w(TAG, "Error: "+ex);
					return;
				}
			}

			
			
			ContentValues cv = new ContentValues();
			cv.put(DirectMessages.COL_FLAGS, flags & ~(Tweets.FLAG_TO_INSERT));
			cv.put(DirectMessages.COL_BUFFER, buffer);
			cv.put(DirectMessages.COL_TEXT, result.getText());
			cv.put(DirectMessages.COL_CREATED, result.getCreatedAt().getTime());
			cv.put(DirectMessages.COL_DMID, result.getId().longValue());
			cv.put(DirectMessages.COL_SENDER, result.getSender().getId());
			cv.put(DirectMessages.COL_RECEIVER, result.getRecipient().getId());
			cv.put(DirectMessages.COL_RECEIVER_SCREENNAME, result.getRecipient().getScreenName());

			
			getContentResolver().update(queryUri, cv, null, null);
			Toast.makeText(getBaseContext(), "Message sent.", Toast.LENGTH_SHORT).show();
			
	     }

	 }


}
