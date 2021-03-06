package com.playuav.android.maps.providers.BaiduMap;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.baidu.mapapi.map.MapStatus;
import com.baidu.mapapi.map.Stroke;
import org.droidplanner.core.gcs.location.Location.LocationReceiver;
import org.droidplanner.core.gcs.location.Location;
import org.droidplanner.core.helpers.coordinates.Coord2D;

import com.o3dr.android.client.Drone;
import com.o3dr.services.android.lib.coordinate.LatLong;
import com.o3dr.services.android.lib.drone.attribute.AttributeEvent;
import com.o3dr.services.android.lib.drone.property.FootPrint;
import com.o3dr.services.android.lib.drone.property.Gps;

import com.playuav.android.DroidPlannerApp;
import com.playuav.android.R;
import com.playuav.android.maps.DPMap;
import com.playuav.android.maps.MarkerInfo;
import com.playuav.android.maps.providers.DPMapProvider;
import com.playuav.android.utils.DroneHelper;
import com.playuav.android.utils.collection.HashBiMap;
import com.playuav.android.utils.prefs.AutoPanMode;
import com.playuav.android.utils.prefs.DroidPlannerPrefs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.BitmapDescriptorFactory;
import com.baidu.mapapi.map.MapStatusUpdate;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.map.Marker;
import com.baidu.mapapi.map.MarkerOptions;
import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.model.LatLngBounds;
import com.baidu.mapapi.map.Polyline;
import com.baidu.mapapi.map.PolylineOptions;
import com.baidu.mapapi.map.Polygon;
import com.baidu.mapapi.map.PolygonOptions;
import com.baidu.mapapi.map.SupportMapFragment;
import com.baidu.mapapi.map.Projection;
import com.baidu.mapapi.map.MapPoi;
import com.baidu.location.BDLocation;
import com.baidu.location.BDLocationListener;
import com.baidu.location.LocationClient;
import com.baidu.location.LocationClientOption;
import com.baidu.mapapi.map.MyLocationConfiguration;
import com.baidu.mapapi.map.MyLocationConfiguration.LocationMode;
import com.baidu.mapapi.map.MyLocationData;

public class BaiduMapFragment extends SupportMapFragment implements DPMap {

    private static final String TAG = BaiduMapFragment.class.getSimpleName();


    public static final String PREF_MAP_TYPE = "pref_map_type";

    public static final String MAP_TYPE_SATELLITE = "Satellite";
    public static final String MAP_TYPE_HYBRID = "Hybrid";
    public static final String MAP_TYPE_NORMAL = "Normal";
    public static final String MAP_TYPE_TERRAIN = "Terrain";

    // TODO: update the interval based on the user's current activity.
    private static final long USER_LOCATION_UPDATE_INTERVAL = 30000; // ms
    private static final long USER_LOCATION_UPDATE_FASTEST_INTERVAL = 10000; // ms
    private static final float USER_LOCATION_UPDATE_MIN_DISPLACEMENT = 15; // m

    private static final float GO_TO_MY_LOCATION_ZOOM = 19f;

    private static final IntentFilter eventFilter = new IntentFilter(AttributeEvent.GPS_POSITION);


    private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final Drone drone = getDroneApi();
            if (!drone.isConnected())
                return;

            final Gps droneGps = drone.getGps();
            if (droneGps == null)
                return;

            if (mPanMode.get() == AutoPanMode.DRONE && droneGps.isValid()) {
                final LatLong droneLocation = droneGps.getPosition();
                updateCamera(droneLocation);
            }
        }
    };

    private final HashBiMap<MarkerInfo, Marker> mBiMarkersMap = new HashBiMap<MarkerInfo, Marker>();

    private DroidPlannerPrefs mAppPrefs;

    private final AtomicReference<AutoPanMode> mPanMode = new AtomicReference<AutoPanMode>(
            AutoPanMode.DISABLED);

 /*   private GoogleApiClientManager.GoogleApiClientTask mGoToMyLocationTask;
    private GoogleApiClientManager.GoogleApiClientTask mRemoveLocationUpdateTask;
    private GoogleApiClientManager.GoogleApiClientTask mRequestLocationUpdateTask;

    private GoogleApiClientManager mGApiClientMgr;*/

    private MapView mMapView;
    private LocationClient mLocClient;
    public MyLocationListenner myListener = new MyLocationListenner();

    private Polyline flightPath;
    private Polyline missionPath;
    private Polyline mDroneLeashPath;
    private int maxFlightPathSize;

    List<LatLng> mdFlightPathList = new ArrayList<LatLng>();
    /*
     * DP Map listeners
     */
    private DPMap.OnMapClickListener mMapClickListener;
    private DPMap.OnMapLongClickListener mMapLongClickListener;
    private DPMap.OnMarkerClickListener mMarkerClickListener;
    private DPMap.OnMarkerDragListener mMarkerDragListener;
    private LocationReceiver mLocationListener;

    protected boolean useMarkerClickAsMapClick = false;

    private List<Polygon> polygonsPaths = new ArrayList<Polygon>();

    protected DroidPlannerApp dpApp;
    private Polygon footprintPoly;

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        dpApp = (DroidPlannerApp) activity.getApplication();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup viewGroup,
                             Bundle bundle) {

        final FragmentActivity activity = getActivity();
        final Context context = activity.getApplicationContext();


        final View view = super.onCreateView(inflater, viewGroup, bundle);

        final BaiduMap.OnMapClickListener onMapClickListener = (new BaiduMap.OnMapClickListener() {
            @Override
            public void onMapClick(LatLng point) {
                if (mMapClickListener != null) {
                    mMapClickListener.onMapClick(DroneHelper.BaiduLatLngToCoord(point));
                }
            }

            @Override
            public boolean onMapPoiClick(MapPoi poi) {
                return false;
            }
        });
        getBaiduMap().setOnMapClickListener(onMapClickListener);

        getBaiduMap().setOnMapLongClickListener(new BaiduMap.OnMapLongClickListener() {
            public void onMapLongClick(LatLng point) {
                if (mMapLongClickListener != null) {
                    mMapLongClickListener.onMapLongClick((DroneHelper.BaiduLatLngToCoord(point)));
                }
            }
        });

        getBaiduMap().setOnMarkerClickListener(new BaiduMap.OnMarkerClickListener() {
             public boolean onMarkerClick(Marker marker)
             {
                 if (useMarkerClickAsMapClick) {
                     onMapClickListener.onMapClick(marker.getPosition());
                     return true;
                 }
                 if (mMarkerClickListener != null) {
                     return mMarkerClickListener.onMarkerClick(mBiMarkersMap.getKey(marker));
                 }
                 return false;
             }
        });

        getBaiduMap().setOnMarkerDragListener(new BaiduMap.OnMarkerDragListener() {
            public void onMarkerDrag(Marker marker)
            {
                if (mMarkerDragListener != null) {
                    final MarkerInfo markerInfo = mBiMarkersMap.getKey(marker);
                    markerInfo.setPosition((DroneHelper.BaiduLatLngToCoord(marker.getPosition())));
                    mMarkerDragListener.onMarkerDrag(markerInfo);
                }
            }
            public void onMarkerDragStart(Marker marker)
            {
                if (mMarkerDragListener != null) {
                    final MarkerInfo markerInfo = mBiMarkersMap.getKey(marker);
                    markerInfo.setPosition((DroneHelper.BaiduLatLngToCoord(marker.getPosition())));
                    mMarkerDragListener.onMarkerDragStart(markerInfo);
                }

            }

            public void  onMarkerDragEnd(Marker marker)
            {
                if (mMarkerDragListener != null) {
                    final MarkerInfo markerInfo = mBiMarkersMap.getKey(marker);
                    markerInfo.setPosition((DroneHelper.BaiduLatLngToCoord(marker.getPosition())));
                    mMarkerDragListener.onMarkerDragEnd(markerInfo);
                }

            }

        });

        mAppPrefs = new DroidPlannerPrefs(context);

        final Bundle args = getArguments();
        if (args != null) {
            maxFlightPathSize = args.getInt(EXTRA_MAX_FLIGHT_PATH_SIZE);
        }

        updateCamera(new LatLong(30.3250427246094,120.063011169434), GO_TO_MY_LOCATION_ZOOM);

        mMapView = getMapView();
        getBaiduMap().setMapType(BaiduMap.MAP_TYPE_SATELLITE);
        getBaiduMap().setMyLocationEnabled(true);
        mLocClient = new LocationClient(context);
        mLocClient.registerLocationListener(myListener);
        LocationClientOption option = new LocationClientOption();
        option.setOpenGps(true);// 打开gps
        option.setCoorType("bd09ll"); // 设置坐标类型
        option.setScanSpan(1000);
        mLocClient.setLocOption(option);
        mLocClient.start();
        Log.v("123", "clientstart");



        return view;//inflater.inflate(R.layout.fragment_baidu_map, viewGroup, false);
    }


    @Override
    public void onPause() {
        // MapView的生命周期与Activity同步，当activity挂起时需调用MapView.onPause()
        if(mMapView != null)
            mMapView.onPause();
        super.onPause();
    }

    @Override
    public void onResume() {
        // MapView的生命周期与Activity同步，当activity恢复时需调用MapView.onResume()
        if(mMapView != null)
            mMapView.onResume();
        super.onResume();
    }

    @Override
    public void onDestroy() {
        // MapView的生命周期与Activity同步，当activity销毁时需调用MapView.destroy()
        mLocClient.stop();

        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
    //    mGApiClientMgr.start();

        if (mPanMode.get() == AutoPanMode.DRONE) {
            LocalBroadcastManager.getInstance(getActivity().getApplicationContext())
                    .registerReceiver(eventReceiver, eventFilter);
        }

    }

    @Override
    public void onStop() {
        super.onStop();
        LocalBroadcastManager.getInstance(getActivity().getApplicationContext())
                .unregisterReceiver(eventReceiver);
     //   mGApiClientMgr.stop();
    }

    @Override
    public void clearFlightPath() {
        if (flightPath != null) {
            mdFlightPathList.clear();
            flightPath.remove();
            flightPath = null;
        }
    }

    @Override
    public LatLong getMapCenter() {
        return DroneHelper.BaiduLatLngToCoord(getBaiduMap().getMapStatus().target);
       // return new LatLong(getBaiduMap().getMapStatus().target.latitude,getBaiduMap().getMapStatus().target.longitude);
    }

    @Override
    public float getMapZoomLevel() {
        return getBaiduMap().getMapStatus().zoom;
    }

    @Override
    public float getMaxZoomLevel() {
        return getBaiduMap().getMaxZoomLevel();
    }

    @Override
    public float getMinZoomLevel() {
        return getBaiduMap().getMinZoomLevel();
    }

    @Override
    public void selectAutoPanMode(AutoPanMode target) {
        final AutoPanMode currentMode = mPanMode.get();
        if (currentMode == target)
            return;

        setAutoPanMode(currentMode, target);
    }

    private Drone getDroneApi() {
        return dpApp.getDrone();
    }

    private void setAutoPanMode(AutoPanMode current, AutoPanMode update) {
        if (mPanMode.compareAndSet(current, update)) {
            switch (current) {
                case DRONE:
                    LocalBroadcastManager.getInstance(getActivity().getApplicationContext())
                            .unregisterReceiver(eventReceiver);
                    break;

                case USER:
               //     if (!mGApiClientMgr.addTask(mRemoveLocationUpdateTask)) {
               //         Log.e(TAG, "Unable to add google api client task.");
                //    }
                    break;

                case DISABLED:
                default:
                    break;
            }

            switch (update) {
                case DRONE:
                    LocalBroadcastManager.getInstance(getActivity().getApplicationContext()).registerReceiver
                            (eventReceiver, eventFilter);
                    break;

                case USER:
                 //   if (!mGApiClientMgr.addTask(mRequestLocationUpdateTask)) {
                 //       Log.e(TAG, "Unable to add google api client task.");
                  //  }
                    break;

                case DISABLED:
                default:
                    break;
            }
        }
    }

    @Override
    public DPMapProvider getProvider() {
        return DPMapProvider.百度地图;
    }

    @Override
    public void addFlightPathPoint(LatLong coord) {
        final LatLng position = DroneHelper.CoordToBaiduLatLang(coord);

        if (maxFlightPathSize > 0) {

            if (mdFlightPathList.size() > maxFlightPathSize) {
                mdFlightPathList.remove(0);
            }
            mdFlightPathList.add(position);

            if (mdFlightPathList.size() <2)
            {
                if(flightPath != null)
                {
                    flightPath.remove();
                    flightPath = null;
                }
                return;
            }

            if (flightPath == null) {

                PolylineOptions  flightPathOptions = new PolylineOptions()
                        .color(FLIGHT_PATH_DEFAULT_COLOR)
                        .width(FLIGHT_PATH_DEFAULT_WIDTH).zIndex(1);

                flightPathOptions.points(mdFlightPathList);
                flightPath = (Polyline)getBaiduMap().addOverlay(flightPathOptions);
            }
            flightPath.setPoints(mdFlightPathList);

        }
    }

    @Override
    public void clearMarkers() {
        for (Marker marker : mBiMarkersMap.valueSet()) {
            marker.remove();
        }

        mBiMarkersMap.clear();
    }

    @Override
    public void updateMarker(MarkerInfo markerInfo) {
        updateMarker(markerInfo, markerInfo.isDraggable());
    }

    @Override
    public void updateMarker(MarkerInfo markerInfo, boolean isDraggable) {
        // if the drone hasn't received a gps signal yet
        final LatLong coord = markerInfo.getPosition();
        if (coord == null) {
            return;
        }
        final LatLng position = DroneHelper.CoordToBaiduLatLang(coord);
        Marker marker = mBiMarkersMap.getValue(markerInfo);
        if (marker == null) {
            // Generate the marker
            generateMarker(markerInfo, position, isDraggable);
        } else {
            // Update the marker
            updateMarker(marker, markerInfo, position, isDraggable);
        }
    }

    private void generateMarker(MarkerInfo markerInfo, LatLng position, boolean isDraggable) {

        Log.v("123","SSSSSSSSSSSSSSSSSSSSSSSSSSSSS");
        final MarkerOptions markerOptions = new MarkerOptions()
                .position(position)
                .draggable(isDraggable)
                .anchor(markerInfo.getAnchorU(), markerInfo.getAnchorV())
                .title(markerInfo.getSnippet()).title(markerInfo.getTitle());

        final Bitmap markerIcon = markerInfo.getIcon(getResources());
        if (markerIcon != null) {
            markerOptions.icon(BitmapDescriptorFactory.fromBitmap(markerIcon));
        }
        else
        {
            markerOptions.icon(BitmapDescriptorFactory
                    .fromResource(R.drawable.ic_marker_white));
        }

        Marker marker = (Marker)getBaiduMap().addOverlay(markerOptions);
        mBiMarkersMap.put(markerInfo, marker);
    }

    private void updateMarker(Marker marker, MarkerInfo markerInfo, LatLng position,
                              boolean isDraggable) {
        final Bitmap markerIcon = markerInfo.getIcon(getResources());
        if (markerIcon != null) {
            marker.setIcon(BitmapDescriptorFactory.fromBitmap(markerIcon));
        }
        marker.setAnchor(markerInfo.getAnchorU(), markerInfo.getAnchorV());
        marker.setPosition(position);
        marker.setRotate(-(markerInfo.getRotation()));
        Log.v("123",String.format("%f",markerInfo.getRotation()));
        marker.setTitle(markerInfo.getSnippet());
        marker.setTitle(markerInfo.getTitle());
        marker.setDraggable(isDraggable);
        marker.setVisible(markerInfo.isVisible());
    }

    @Override
    public void updateMarkers(List<MarkerInfo> markersInfos) {
        for (MarkerInfo info : markersInfos) {
            updateMarker(info);
        }
    }

    @Override
    public void updateMarkers(List<MarkerInfo> markersInfos, boolean isDraggable) {
        for (MarkerInfo info : markersInfos) {
            updateMarker(info, isDraggable);
        }
    }

    @Override
    public Set<MarkerInfo> getMarkerInfoList() {
        return new HashSet<MarkerInfo>(mBiMarkersMap.keySet());
    }

    @Override
    public List<LatLong> projectPathIntoMap(List<LatLong> path) {
        List<LatLong> coords = new ArrayList<LatLong>();
        Projection projection = getBaiduMap().getProjection();

        for (LatLong point : path) {
            LatLng coord = projection.fromScreenLocation(new Point((int) point
                    .getLatitude(), (int) point.getLongitude()));
            coords.add(DroneHelper.BaiduLatLngToCoord(coord));
        }

        return coords;
    }

    @Override
    public void removeMarkers(Collection<MarkerInfo> markerInfoList) {
        Log.v(TAG,"remove marker");
        if (markerInfoList == null || markerInfoList.isEmpty()) {
            return;
        }

        for (MarkerInfo markerInfo : markerInfoList) {
            Marker marker = mBiMarkersMap.getValue(markerInfo);
            if (marker != null) {
                marker.remove();
                mBiMarkersMap.removeKey(markerInfo);
            }
        }
    }

    @Override
    public void setMapPadding(int left, int top, int right, int bottom) {
     //   getMap().setPadding(left, top, right, bottom);
    }

    @Override
    public void setOnMapClickListener(OnMapClickListener listener) {
        mMapClickListener = listener;
    }

    @Override
    public void setOnMapLongClickListener(OnMapLongClickListener listener) {
        mMapLongClickListener = listener;
    }

    @Override
    public void setOnMarkerDragListener(OnMarkerDragListener listener) {
        mMarkerDragListener = listener;
    }

    @Override
    public void setOnMarkerClickListener(OnMarkerClickListener listener) {
        mMarkerClickListener = listener;
    }

    @Override
    public void setLocationListener(LocationReceiver receiver) {
        mLocationListener = receiver;

        //Update the listener with the last received location
       /* if (mLocationListener != null && isResumed()) {
            mGApiClientMgr.addTask(mGApiClientMgr.new GoogleApiClientTask() {
                @Override
                protected void doRun() {
                    final Location lastLocation = LocationServices.FusedLocationApi.getLastLocation
                            (getGoogleApiClient());
                    if (lastLocation != null) {
                        mLocationListener.onLocationChanged(lastLocation);
                    }
                }
            });
        }*/
    }

    private void updateCamera(final LatLong coord){
        if(coord != null){
            final float zoomLevel = getBaiduMap().getMapStatus().zoom;
            getBaiduMap().animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(DroneHelper.CoordToBaiduLatLang(coord), zoomLevel));
        }
    }

    @Override
    public void updateCamera(final LatLong coord, final float zoomLevel) {
        if (coord != null) {
            getBaiduMap().animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(DroneHelper.CoordToBaiduLatLang(coord), zoomLevel));
        }
    }

    @Override
    public void updateCameraBearing(float bearing) {

        MapStatus ms = new MapStatus.Builder(getBaiduMap().getMapStatus()).rotate(bearing).build();
        MapStatusUpdate u = MapStatusUpdateFactory.newMapStatus(ms);
        getBaiduMap().animateMapStatus(u);
    }

    @Override
    public void updateDroneLeashPath(PathSource pathSource) {
        List<LatLong> pathCoords = pathSource.getPathPoints();

        final List<LatLng> pathPoints = new ArrayList<LatLng>(pathCoords.size());
        for (LatLong coord : pathCoords) {
            pathPoints.add(DroneHelper.CoordToBaiduLatLang(coord));
        }

        if (pathPoints.size() <2)
        {
            if(mDroneLeashPath != null)
            {
                mDroneLeashPath.remove();
                mDroneLeashPath = null;
            }
            return;
        }

        if (mDroneLeashPath == null) {
            PolylineOptions flightPath = new PolylineOptions();
            flightPath.color(DRONE_LEASH_DEFAULT_COLOR).width(
                    DroneHelper.scaleDpToPixels(DRONE_LEASH_DEFAULT_WIDTH,
                            getResources()));
            flightPath.points(pathPoints);
            mDroneLeashPath = (Polyline)getBaiduMap().addOverlay(flightPath);
        }
        mDroneLeashPath.setPoints(pathPoints);
    }

    @Override
    public void updateMissionPath(PathSource pathSource) {
        List<LatLong> pathCoords = pathSource.getPathPoints();
        final List<LatLng> pathPoints = new ArrayList<LatLng>(pathCoords.size());
        for (LatLong coord : pathCoords) {
            pathPoints.add(DroneHelper.CoordToBaiduLatLang(coord));
        }

        if (pathPoints.size() <2)
        {
            if(missionPath != null)
            {
                missionPath.remove();
                missionPath = null;
            }
            return;
        }

        if (missionPath == null) {

            PolylineOptions pathOptions = new PolylineOptions();
            pathOptions.color(MISSION_PATH_DEFAULT_COLOR).width(
                    MISSION_PATH_DEFAULT_WIDTH);
            pathOptions.points(pathPoints);
            missionPath = (Polyline)getBaiduMap().addOverlay(pathOptions);
        }


        missionPath.setPoints(pathPoints);
    }


    @Override
    public void updatePolygonsPaths(List<List<LatLong>> paths) {
        for (Polygon poly : polygonsPaths) {
            poly.remove();
        }

        for (List<LatLong> contour : paths) {
            PolygonOptions pathOptions = new PolygonOptions();
            pathOptions.stroke(new Stroke(POLYGONS_PATH_DEFAULT_WIDTH,POLYGONS_PATH_DEFAULT_COLOR));
            final List<LatLng> pathPoints = new ArrayList<LatLng>(contour.size());
            for (LatLong coord : contour) {
                pathPoints.add(DroneHelper.CoordToBaiduLatLang(coord));
            }
            pathOptions.points(pathPoints);
            polygonsPaths.add((Polygon)getBaiduMap().addOverlay(pathOptions));
        }

    }

    @Override
    public void addCameraFootprint(FootPrint footprintToBeDraw) {
       PolygonOptions pathOptions = new PolygonOptions();
       pathOptions.stroke(new Stroke(FOOTPRINT_DEFAULT_WIDTH,FOOTPRINT_DEFAULT_COLOR));
       pathOptions.fillColor(FOOTPRINT_FILL_COLOR);
       final List<LatLng> pathPoints = new ArrayList<LatLng>();
       for (LatLong vertex : footprintToBeDraw.getVertexInGlobalFrame()) {
            pathPoints.add(DroneHelper.CoordToBaiduLatLang(vertex));
       }
       pathOptions.points(pathPoints);
       getBaiduMap().addOverlay(pathOptions);

    }

    /**
     * Save the map camera state on a preference file
     * http://stackoverflow.com/questions
     * /16697891/google-maps-android-api-v2-restoring
     * -map-state/16698624#16698624
     */
    @Override
    public void saveCameraPosition() {
        MapStatus camera = getBaiduMap().getMapStatus();
        mAppPrefs.prefs.edit()
                .putFloat("BAIDU_LAT", (float) camera.target.latitude)
                .putFloat("BAIDU_LNG", (float) camera.target.longitude)
                .putFloat("BAIDU_BEA", camera.rotate)
                .putFloat("BAIDU_TILT", camera.overlook)
                .putFloat("BAIDU_ZOOM", camera.zoom).apply();
    }

    @Override
    public void loadCameraPosition() {
        final SharedPreferences settings = mAppPrefs.prefs;

        MapStatus.Builder camera = new MapStatus.Builder();
        camera.rotate(settings.getFloat("BAIDU_BEA", DEFAULT_BEARING));
        camera.overlook(settings.getFloat("BAIDU_TILT", DEFAULT_TILT));
        camera.zoom(settings.getFloat("BAIDU_ZOOM", DEFAULT_ZOOM_LEVEL));
        camera.target(new LatLng(settings.getFloat("BAIDU_LAT", DEFAULT_LATITUDE),
                settings.getFloat("BAIDU_LNG", DEFAULT_LONGITUDE)));

        getBaiduMap().setMapStatus(MapStatusUpdateFactory.newMapStatus(camera.build()));

    }



    @Override
    public void zoomToFit(List<LatLong> coords) {
        if (!coords.isEmpty()) {
            final List<LatLng> points = new ArrayList<LatLng>();
            for (LatLong coord : coords)
                points.add(DroneHelper.CoordToBaiduLatLang(coord));

            final LatLngBounds bounds = getBounds(points);
            MapStatusUpdate update = MapStatusUpdateFactory.newLatLngBounds(bounds);
            getBaiduMap().animateMapStatus(update);
        }
    }

    @Override
    public void zoomToFitMyLocation(final List<LatLong> coords) {
        MyLocationData locationData = getBaiduMap().getLocationData();
        if (locationData != null) {
            final List<LatLong> updatedCoords = new ArrayList<LatLong>(coords);
            updatedCoords.add(DroneHelper.BDLocationToCoord(locationData));
            zoomToFit(updatedCoords);
        } else {
            zoomToFit(coords);
        }
    }

    @Override
    public void goToMyLocation() {
       // if (!mGApiClientMgr.addTask(mGoToMyLocationTask)) {
        //    Log.e(TAG, "Unable to add google api client task.");
        MyLocationData locationData = getBaiduMap().getLocationData();
        if(locationData != null)
            updateCamera(DroneHelper.BDLocationToCoord(locationData), GO_TO_MY_LOCATION_ZOOM);


    }

    @Override
    public void goToDroneLocation() {
        Drone dpApi = getDroneApi();
        if (!dpApi.isConnected())
            return;

        Gps gps = dpApi.getGps();
        if (!gps.isValid()) {
            Toast.makeText(getActivity().getApplicationContext(), R.string.drone_no_location, Toast.LENGTH_SHORT).show();
            return;
        }

        final float currentZoomLevel = getBaiduMap().getMapStatus().zoom;
        final LatLong droneLocation = gps.getPosition();
        updateCamera(droneLocation, (int) currentZoomLevel);
    }


    private LatLngBounds getBounds(List<LatLng> pointsList) {
        LatLngBounds.Builder builder = new LatLngBounds.Builder();
        for (LatLng point : pointsList) {
            builder.include(point);
        }
        return builder.build();
    }


    @Override
    public void skipMarkerClickEvents(boolean skip) {
        useMarkerClickAsMapClick = skip;
    }

    @Override
    public void updateRealTimeFootprint(FootPrint footprint) {
        if (footprintPoly == null) {
            PolygonOptions pathOptions = new PolygonOptions();
            pathOptions.stroke(new Stroke(FOOTPRINT_DEFAULT_WIDTH,FOOTPRINT_DEFAULT_COLOR));
            pathOptions.fillColor(FOOTPRINT_FILL_COLOR);
            final List<LatLng> pathPoints = new ArrayList<LatLng>();
            for (LatLong vertex : footprint.getVertexInGlobalFrame()) {
                pathPoints.add(DroneHelper.CoordToBaiduLatLang(vertex));
            }
            pathOptions.points(pathPoints);
            footprintPoly = (Polygon)getBaiduMap().addOverlay(pathOptions);
        } else {
            List<LatLng> list = new ArrayList<LatLng>();
            for (LatLong vertex : footprint.getVertexInGlobalFrame()) {
                list.add(DroneHelper.CoordToBaiduLatLang(vertex));
            }
            footprintPoly.setPoints(list);
        }

    }



    public class MyLocationListenner implements BDLocationListener {

        @Override
        public void onReceiveLocation(BDLocation location) {
            // map view 销毁后不在处理新接收的位置
            if (location == null || mMapView == null)
                return;
            MyLocationData locData = new MyLocationData.Builder()
                    .accuracy(location.getRadius())
                            // 此处设置开发者获取到的方向信息，顺时针0-360
                    .direction(100).latitude(location.getLatitude())
                    .longitude(location.getLongitude()).build();
            getBaiduMap().setMyLocationData(locData);

            LatLong latlong = DroneHelper.BDLocationToCoord(locData);
            if (mPanMode.get() == AutoPanMode.USER) {
                updateCamera(latlong, (int) getBaiduMap().getMapStatus().zoom);
            }

            if (mLocationListener != null) {
                Location loc = new Location(new Coord2D(latlong.getLatitude(),latlong.getLongitude()),locData.direction,locData.speed,locData.satellitesNum>3);
                mLocationListener.onLocationChanged(loc);
            }


        }

        public void onReceivePoi(BDLocation poiLocation) {
        }
    }
}

