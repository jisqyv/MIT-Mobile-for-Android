package edu.mit.mitmobile2;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import edu.mit.mitmobile2.about.BuildSettings;
import edu.mit.mitmobile2.classes.SharedData;
import edu.mit.mitmobile2.emergency.EmergencyDB;
import edu.mit.mitmobile2.facilities.FacilitiesDB;
import edu.mit.mitmobile2.objs.CourseItem;
import edu.mit.mitmobile2.objs.EventDetailsItem;
import edu.mit.mitmobile2.objs.EmergencyItem.Contact;
import edu.mit.mitmobile2.objs.MapCatItem;
import edu.mit.mitmobile2.objs.NewsItem;
//import com.thoughtworks.xstream.XStream;
//import com.thoughtworks.xstream.io.json.JsonHierarchicalStreamDriver;

public class Global extends Application {

	public static final boolean DEBUG = false;
	public Context mContext;
	
	// Shared preferences MUST use separate entries (docs say otherwise but seen failures to commit edits)
	public static final String PREFS = "prefs";
	public static final String PREFS_SHUTTLES = "prefs_shuttles";
	public static final String PREFS_STELLAR = "prefs_stellar";
	public static final String PREFS_MAP = "prefs_map";
	
	public static final String PREF_KEY_STOPS = "pref_stops";
	public static final String PREF_KEY_EMERGENCY_VERSION = "pref_version";
	
	public static EventDetailsItem curEvent;
	public static CourseItem curCourse;
	public static NewsItem curNews;

	// Mobile Server 
	private static final String TAG = "Global";
	public static final String MIT_MOBILE_SERVER_KEY = "mit_mobile_server"; // key for server variable in the preferences file
	public static final String DEFAULT_MIT_MOBILE_SERVER = BuildSettings.MOBILE_WEB_DOMAIN; // key for server variable in the preferences file
	private static String mobileWebDomain = DEFAULT_MIT_MOBILE_SERVER;
	private static Map version = null;
	private Resources res;

	// Shared Data
	public static final SharedData sharedData = new SharedData();
	
	// Facilities 
	private static String problemType;
	
	@Override
	public void onCreate() {
		super.onCreate();
		Log.d(TAG,"onCreate()");
		mContext = this; 
		// load Mobile Web Domain preferences
		try {
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
			Global.setMobileWebDomain(prefs.getString(Global.MIT_MOBILE_SERVER_KEY, null));
		}
		catch (RuntimeException e) {
			Log.d(TAG,"error getting prefs: " + e.getMessage() + "\n" + e.getStackTrace());
		}
		// if the mobile server is not defined in the prefernces, default it to the value in the BuildSettings.java file
		if (Global.getMobileWebDomain() == null) {
			Global.setMobileWebDomain(Global.DEFAULT_MIT_MOBILE_SERVER);
		}
		
		res = this.getResources();
		
		Handler uiHandler = new Handler();

		Global.getVersionMap(mContext, uiHandler);

		Global.updateData(mContext, uiHandler);
	}

	// Maps related:
	public static ArrayList<MapCatItem> curSubCats;

	public static String getMobileWebDomain() {
		Log.d(TAG,"mobileWebDomain from get = " + mobileWebDomain);
		return Global.mobileWebDomain;
	}
	
	public static void setMobileWebDomain(String mobileWebDomain) {
		Global.mobileWebDomain = mobileWebDomain;
	}
	
	// Facilities Related
	public static void setProblemType(String problemType) {
		Global.problemType = problemType;
		Log.d(TAG,problemType + " selected");
	}

	public static String getProblemType() {
		return Global.problemType;
	}
	
	public static class URLReader {
	    public static String get(String urlString) throws Exception {
	    	String contents = "";
	    	Log.d(TAG,"urlString = " + urlString);
	    	URL url = new URL(urlString);
	    	BufferedReader in = new BufferedReader(
						new InputStreamReader(url.openStream()));
	    	String inputLine;

	    	while ((inputLine = in.readLine()) != null)
	    		contents += inputLine;

	    	in.close();
	    	return contents;
	    }
	}

	
	public static HashMap getVersionMap(final Context mContext,final Handler uiHandler) {
		// uses the version api to get a json string of all databases and their version numbers and returns them as a hash map
		// this hashmap can be used to determine if the local copy of the database is out of date and needs to be updated
    	Log.d(TAG,"getVersionMap()");
    	if (Global.version == null) {
    		Global.version = new HashMap();
    	}
    	
		MobileWebApi api = new MobileWebApi(false, true, "Version", mContext, uiHandler);
		HashMap<String, String> params = new HashMap<String, String>();
		params.put("module", "version");
		params.put("command", "list");
    	Log.d(TAG,"before request json");
		final HashMap serverVersionMap = new HashMap();

		api.requestJSONObject(params, new MobileWebApi.JSONObjectResponseListener(
                new MobileWebApi.DefaultErrorListener(uiHandler),
                new MobileWebApi.DefaultCancelRequestListener(uiHandler)) {
			@Override
			public void onResponse(JSONObject obj) {
				String versionKey;

				try {
					Iterator m = obj.keys();
					while (m.hasNext()) {

						String module = (String)m.next();
						if (!serverVersionMap.containsKey(module)) {
							serverVersionMap.put(module, new HashMap());
						}
						JSONObject data = (JSONObject)obj.get(module);
						Iterator d = data.keys();
						while (d.hasNext()) {
							String key = (String)d.next();
							String version = (String)data.getString(key);
							HashMap moduleMap = (HashMap)serverVersionMap.get(module);
							moduleMap.put(key,version);
						}
					}
				}
				catch (JSONException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				MobileWebApi.sendSuccessMessage(uiHandler);
			}
			
			public void onError(JSONObject obj) {
				Log.d(TAG,"error");				
			}
		});
    	
    	Global.version = serverVersionMap;
    	return serverVersionMap;

	}
	
	public static boolean upToDate(String module, Integer localVersion, String remoteKey) {
		// compares the version of the local data against the version on the mobile server.
		// returns true if the local version is greater than or equal to the server version
		Integer remoteVersion = Global.getVersion(module,remoteKey);
		return (localVersion >= remoteVersion);
	}
	
	private static void updateData(Context mContext,final Handler uiHandler) {
		// retrieves the version map from the server
		// for each entry in the map, compares the remote version to the local version and calls an appropriate function to update the data if it is out of date

		int localVersion;
		int serverVersion;

		HashMap serverVersionMap = (HashMap)Global.getVersionMap(mContext, uiHandler);
		for (Iterator it = serverVersionMap.keySet().iterator(); it.hasNext();) {
			Object key = it.next();
			localVersion = Integer.parseInt((String)Global.version.get(key));
			serverVersion = Integer.parseInt((String)serverVersionMap.get(key));
			   
			if (serverVersion > localVersion) {
				Log.d(TAG,"updating " + key);
			}
		}
	}
	
	public static Integer getVersion(String module,String key) {
		HashMap version = (HashMap)Global.version.get(module);
		return Integer.parseInt(version.get(key).toString());
	}
		
}
