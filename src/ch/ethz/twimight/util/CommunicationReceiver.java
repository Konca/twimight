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
package ch.ethz.twimight.util;

import ch.ethz.twimight.activities.LoginActivity;
import ch.ethz.twimight.net.tds.TDSAlarm;
import ch.ethz.twimight.net.twitter.TwitterService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

/**
 * Listends for changes in connectivity and starts the TDSThread if a new connection
 * is detected.
 * @author thossmann
 *
 */
public class CommunicationReceiver extends BroadcastReceiver {
	
	private static final String TAG = "CommunicationReceiver";

	@Override
	public void onReceive(Context context, Intent intent) {
		
		// connectivity changed!
		NetworkInfo currentNetworkInfo = (NetworkInfo) intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
		
		// are we connected and logged in?
		if(currentNetworkInfo.isConnected() && LoginActivity.hasAccessToken(context) && LoginActivity.hasAccessTokenSecret(context)){
			try{
				// TDS communication
				if(TDSAlarm.isTdsEnabled(context)){
					// remove currently scheduled updates and schedule an immediate one
					new TDSAlarm();
				}
				
				// Trigger Twitter synch
				Intent i = new Intent(context, TwitterService.class);
				i.putExtra("synch_request", TwitterService.SYNCH_ALL);
				context.startService(i);
				
			} catch (Exception e) {
				Log.e(TAG, "Error on connectivity change");
			}
		}
			
		
	}
	
	
}
