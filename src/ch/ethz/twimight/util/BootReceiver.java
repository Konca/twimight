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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceManager;
import ch.ethz.twimight.activities.LoginActivity;
import ch.ethz.twimight.net.opportunistic.ScanningAlarm;
import ch.ethz.twimight.net.tds.TDSAlarm;
import ch.ethz.twimight.net.twitter.TwitterAlarm;

/**
 * Starts the updater service after the boot process.
 * @author pcarta
 * @author thossmann
 *
 */
public class BootReceiver extends BroadcastReceiver {
	
	/**
	 * Starts the twimight services upon receiving a boot Intent.
	 * @param context
	 * @param intent
	 */
	@Override
	public void onReceive(Context context, Intent intent) {
		
		// we only start the services if we are logged in (i.e., we have the tokens from twitter)
		if(LoginActivity.hasAccessToken(context) && LoginActivity.hasAccessTokenSecret(context)){
			
			
			// Start the service for communication with the TDS
			if(PreferenceManager.getDefaultSharedPreferences(context).getBoolean("prefTDSCommunication", Constants.TDS_DEFAULT_ON)==true){
				new TDSAlarm(context.getApplicationContext(), Constants.TDS_UPDATE_INTERVAL);
			}
			
			
			if(PreferenceManager.getDefaultSharedPreferences(context).getBoolean("prefDisasterMode", Constants.DISASTER_DEFAULT_ON)==true){
				new ScanningAlarm(context.getApplicationContext(),false);
			}
			
						
			if(PreferenceManager.getDefaultSharedPreferences(context).getBoolean("prefRunAtBoot", Constants.TWEET_DEFAULT_RUN_AT_BOOT)==true){
				new TwitterAlarm(context.getApplicationContext(),false);
			}
		}

	}
	
}
