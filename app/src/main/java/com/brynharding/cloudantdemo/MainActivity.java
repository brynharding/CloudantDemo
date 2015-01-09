package com.brynharding.cloudantdemo;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLEncoder;

public class MainActivity extends Activity implements OnMapReadyCallback, GoogleMap.OnCameraChangeListener {

    private static String JSON_BOOKMARK_KEY = "bookmark";
    private static String JSON_ROWS_KEY = "rows";
    private static String JSON_FIELDS_KEY = "fields";
    private static String JSON_LAT_KEY = "lat";
    private static String JSON_LON_KEY = "lon";
    private static String JSON_NAME_KEY = "name";

    private static double MIN_LONG = -180;
    private static double MAX_LONG = 180;

    private static LatLng INITIAL_POSITION = new LatLng(51.4507389, -2.5820486); // IBM Bristol.
    private static float INITIAL_ZOOM = 10f; // Arbitrary zoom level.

    private GoogleMap mMap;
    private GetAirportsTask mGetAirportsTask;
    private ProgressBar mProgressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ((MapFragment) getFragmentManager().findFragmentById(R.id.map)).getMapAsync(this);
        mProgressBar = (ProgressBar) findViewById(R.id.progress_spinner);

        if (!networkConnected(this)) {
            Toast.makeText(this, R.string.no_network, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onMapReady(GoogleMap map) {
        map.setOnCameraChangeListener(this);
        mMap = map;

        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(INITIAL_POSITION, INITIAL_ZOOM));

        // Override marker click so we only open/close the info window and don't re-centre map.
        mMap.setOnMarkerClickListener(new GoogleMap.OnMarkerClickListener() {
            Marker currentShown;

            public boolean onMarkerClick(Marker marker) {
// Would like to use this code, but can't because of bug https://code.google.com/p/gmaps-api-issues/issues/detail?id=5408
//                if (marker.isInfoWindowShown()) {
//                    marker.hideInfoWindow();
//                } else {
//                    marker.showInfoWindow();
//                }
//                return true;
                if (marker.equals(currentShown)) {
                    marker.hideInfoWindow();
                    currentShown = null;
                } else {
                    marker.showInfoWindow();
                    currentShown = marker;
                }
                return true;
            }
        });
    }

    private LatLngBounds getBounds() {
        return mMap != null ? mMap.getProjection().getVisibleRegion().latLngBounds : null;
    }

    @Override
    public void onCameraChange(CameraPosition cameraPosition) {
        if (mGetAirportsTask != null) {
            mGetAirportsTask.cancel(true);
        }
        mGetAirportsTask = new GetAirportsTask();
        mGetAirportsTask.execute(getBounds());
    }

    private class MarkerInfo {
        LatLng latLng;
        String name;
    }

    private class GetAirportsTask extends AsyncTask<LatLngBounds, MarkerInfo, Void> {
        private boolean mMoreData;
        private String mBookmark;

        @Override
        protected void onPreExecute() {
            mProgressBar.setVisibility(View.VISIBLE);

            // Remove existing markers.
            if (mMap != null) {
                mMap.clear();
            }
        }

        @Override
        protected Void doInBackground(LatLngBounds... params) {
            final String urlPrefix = getString(R.string.server_url) + "/" + getString(R.string.airport_search_service);
            final LatLng sw = params[0].southwest;
            final LatLng ne = params[0].northeast;

            String[] latLongArgs;
            if (sw.longitude > ne.longitude) {
                // The region crosses the -180/180 longitude, so we use two queries as the single query won't retrieve
                // the results.
                latLongArgs = new String[]{
                        getString(R.string.airport_search_service_args, sw.longitude, MAX_LONG, sw.latitude, ne.latitude),
                        getString(R.string.airport_search_service_args, MIN_LONG, ne.longitude, sw.latitude, ne.latitude)};

            } else {
                latLongArgs = new String[]{getString(R.string.airport_search_service_args, sw.longitude, ne.longitude, sw.latitude, ne.latitude)};
            }

            for (String latLongArg : latLongArgs) {
                try {
                    StringBuilder sb = new StringBuilder(urlPrefix);
                    sb.append(URLEncoder.encode(latLongArg, "UTF-8"));
                    String query = sb.toString();
                    mMoreData = true;
                    mBookmark = null;
                    while (mMoreData && !isCancelled()) {
                        // Reset the query.
                        sb.setLength(0);
                        sb.append(query);

                        // Append the bookmark if we need multiple queries to retrieve the data.
                        if (mBookmark != null) {
                            sb.append("&").append(JSON_BOOKMARK_KEY).append("=").append(mBookmark);
                        }

                        HttpClient httpClient = new DefaultHttpClient();
                        String url = sb.toString();
                        HttpGet httpGet = new HttpGet(url);
                        HttpResponse response = httpClient.execute(httpGet);

                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getEntity().getContent(), "UTF-8"));
                        String result = reader.readLine();

                        JSONObject json = new JSONObject(result);

                        mBookmark = json.getString(JSON_BOOKMARK_KEY);
                        JSONArray rows = json.getJSONArray(JSON_ROWS_KEY);

                        for (int i = 0; i < rows.length(); ++i) {
                            JSONObject row = rows.getJSONObject(i);
                            JSONObject fields = row.getJSONObject(JSON_FIELDS_KEY);
                            MarkerInfo markerInfo = new MarkerInfo();
                            markerInfo.latLng = new LatLng(fields.getDouble(JSON_LAT_KEY), fields.getDouble(JSON_LON_KEY));
                            markerInfo.name = fields.getString(JSON_NAME_KEY);
                            publishProgress(markerInfo);
                        }
                        mMoreData = rows.length() != 0;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, getString(R.string.error_text), Toast.LENGTH_SHORT).show();
                        }
                    });
                    return null;
                }
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(MarkerInfo... markerInfo) {
            mMap.addMarker(new MarkerOptions()
                    .position(markerInfo[0].latLng)
                    .title(markerInfo[0].name));
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            mProgressBar.setVisibility(View.INVISIBLE);
        }
    }

    public static boolean networkConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

}
