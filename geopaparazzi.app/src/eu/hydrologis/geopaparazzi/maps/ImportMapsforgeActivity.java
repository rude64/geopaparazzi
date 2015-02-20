/*
 * Geopaparazzi - Digital field mapping on Android based devices
 * Copyright (C) 2010  HydroloGIS (www.hydrologis.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package eu.hydrologis.geopaparazzi.maps;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.KeyEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;

import org.mapsforge.core.model.GeoPoint;
import org.mapsforge.core.model.Tag;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.reader.MapDatabase;
import org.mapsforge.map.reader.MapReadResult;
import org.mapsforge.map.reader.PointOfInterest;
import org.mapsforge.map.reader.Way;
import org.mapsforge.map.reader.header.FileOpenResult;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.TreeSet;

import eu.geopaparazzi.library.database.GPLog;
import eu.geopaparazzi.library.database.GPLogPreferencesHandler;
import eu.geopaparazzi.library.gpx.parser.RoutePoint;
import eu.geopaparazzi.library.gpx.parser.WayPoint;
import eu.geopaparazzi.library.util.LibraryConstants;
import eu.geopaparazzi.library.util.StringAsyncTask;
import eu.geopaparazzi.library.util.Utilities;
import eu.geopaparazzi.library.util.activities.LogAnalysisActivity;
import eu.geopaparazzi.mapsforge.mapsdirmanager.MapsDirManager;
import eu.geopaparazzi.mapsforge.mapsdirmanager.maps.tiles.MapTable;
import eu.geopaparazzi.spatialite.database.spatial.SpatialDatabasesManager;
import eu.geopaparazzi.spatialite.database.spatial.core.databasehandlers.AbstractSpatialDatabaseHandler;
import eu.geopaparazzi.spatialite.database.spatial.core.databasehandlers.SpatialiteDatabaseHandler;
import eu.geopaparazzi.spatialite.database.spatial.core.tables.AbstractSpatialTable;
import eu.hydrologis.geopaparazzi.GeopaparazziApplication;
import eu.hydrologis.geopaparazzi.R;
import eu.hydrologis.geopaparazzi.database.DaoGpsLog;
import eu.hydrologis.geopaparazzi.database.DaoNotes;
import eu.hydrologis.geopaparazzi.database.SqlViewActivity;
import eu.hydrologis.geopaparazzi.util.MapsforgeExtractedFormHelper;

/**
 * The mapsforge data extraction activity.
 *
 * @author Andrea Antonello (www.hydrologis.com)
 */
public class ImportMapsforgeActivity extends Activity {

    private float[] nswe;
    private int zoomLevel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.extract_mapsforge_view);

        Bundle extras = getIntent().getExtras();
        nswe = extras.getFloatArray(LibraryConstants.NSWE);
        zoomLevel = extras.getInt(LibraryConstants.ZOOMLEVEL);
    }


    /**
     * Start data extraction.
     *
     * @param view parent.
     */
    public void startExtraction(View view) {

        Date date = new Date();
        final long dateLong = date.getTime();

        CheckBox poisCheckbox = (CheckBox) findViewById(R.id.poisCheckbox);
        final boolean doPois = poisCheckbox.isChecked();
        CheckBox waysCheckbox = (CheckBox) findViewById(R.id.waysCheckbox);
        final boolean doWays = waysCheckbox.isChecked();

        if (!doPois && !doWays) {
            return;
        }

        CheckBox contoursCheckbox = (CheckBox) findViewById(R.id.contoursCheckbox);
        final boolean doContours = contoursCheckbox.isChecked();

        EditText filterEditTExt = (EditText) findViewById(R.id.filterEditText);
        final String filter = filterEditTExt.getText().toString().toLowerCase();

        CheckBox excludeCheckBox = (CheckBox) findViewById(R.id.excludeFilterCheckbox);
        final boolean filterExcludes = excludeCheckBox.isChecked();

        AbstractSpatialTable selectedSpatialTable = MapsDirManager.getInstance().getSelectedSpatialTable();
        if (selectedSpatialTable instanceof MapTable) {
            MapTable mapTable = (MapTable) selectedSpatialTable;
            File databaseFile = mapTable.getDatabaseFile();
            final MapDatabase mapFile = new MapDatabase();

            FileOpenResult result = mapFile.openFile(databaseFile);
            final double w = nswe[2];
            final double s = nswe[1];
            final double e = nswe[3];
            final double n = nswe[0];
            final byte z = (byte) zoomLevel;

            // prepare count for various levels, we go a few into detail

            long count = 0;
            final int zoomLimit = 4;
            for (int i = 0; i <= zoomLimit; i++) {
                int zoom = z + i;
                if (zoom > 22) {
                    break;
                }
                long startXTile = MercatorProjection.longitudeToTileX(w, (byte) zoom);
                long endXTile = MercatorProjection.longitudeToTileX(e, (byte) zoom);
                long startYTile = MercatorProjection.latitudeToTileY(n, (byte) zoom);
                long endYTile = MercatorProjection.latitudeToTileY(s, (byte) zoom);
                count = count + (endXTile - startXTile) * (endYTile - startYTile);
            }


            StringAsyncTask task = new StringAsyncTask(this) {
                @Override
                protected String doBackgroundWork() {
                    try {
                        TreeSet<String> pointsSet = new TreeSet<String>();
                        TreeSet<String> waysSet = new TreeSet<String>();
                        boolean doFilter = filter.length() > 0;
                        int index = 0;
                        for (int i = 0; i <= zoomLimit; i++) {
                            int zoom = z + i;
                            if (zoom > 22) {
                                break;
                            }
                            long startXTile = MercatorProjection.longitudeToTileX(w, (byte) zoom);
                            long endXTile = MercatorProjection.longitudeToTileX(e, (byte) zoom);
                            long startYTile = MercatorProjection.latitudeToTileY(n, (byte) zoom);
                            long endYTile = MercatorProjection.latitudeToTileY(s, (byte) zoom);
                            for (long tileX = startXTile; tileX <= endXTile; tileX++) {
                                for (long tileY = startYTile; tileY <= endYTile; tileY++) {
                                    index++;
                                    Tile tile = new Tile(tileX, tileY, (byte) zoom);
                                    MapReadResult mapReadResult = mapFile.readMapData(tile);

                                    if (doPois) {
                                        for (PointOfInterest pointOfInterest : mapReadResult.pointOfInterests) {
                                            GeoPoint p = pointOfInterest.position;
                                            double longitude = p.getLongitude();
                                            double latitude = p.getLatitude();

                                            if (longitude < w || longitude > e || latitude < s || latitude > n) {
                                                // ignore external points
                                                continue;
                                            }

                                            MapsforgeExtractedFormHelper mapsforgeHelper = new MapsforgeExtractedFormHelper();
                                            List<Tag> tags = pointOfInterest.tags;
                                            double elev = -1.0;
                                            for (Tag tag : tags) {
                                                String key = tag.key;
                                                String value = tag.value;
                                                if (key.equals("elev")) {
                                                    try {
                                                        elev = Double.parseDouble(value);
                                                    } catch (Exception e1) {
                                                        // ignore
                                                    }
                                                }
                                                mapsforgeHelper.addTag(key, value);
                                            }

                                            String form = mapsforgeHelper.toForm();
                                            String labelValue = mapsforgeHelper.getLabelValue();

                                            // check if the complete text contains the thing
                                            if (doFilter) {
                                                String formLC = form.toLowerCase();
                                                if (filterExcludes && formLC.contains(filter)) {
                                                    continue;
                                                } else if (!filterExcludes && !formLC.contains(filter)) {
                                                    continue;
                                                }
                                            }
                                            try {
                                                // check if it is double
                                                String key = longitude + "_" + latitude + "_" + form;
                                                if (pointsSet.add(key)) {
                                                    DaoNotes.addNote(longitude, latitude, elev, dateLong, labelValue, "POI", form, null);
                                                }
                                            } catch (IOException ex) {
                                                GPLog.error(this, null, ex);
                                            }
                                        }
                                    }
                                    // TODO
                                    if (i == 0 && doWays) {
                                        SQLiteDatabase sqliteDatabase = GeopaparazziApplication.getInstance().getDatabase();
                                        List<Way> ways = mapReadResult.ways;
                                        for (Way way : ways) {

                                            DaoGpsLog log = new DaoGpsLog();

                                            List<Tag> tags = way.tags;

                                            boolean isRoad = false;
                                            boolean isContour = false;
                                            String name = null;
                                            for (Tag tag : tags) {
                                                String key = tag.key;
                                                String value = tag.value;
                                                if (key.equals("highway")) {
                                                    isRoad = true;
                                                    if (name == null) {
                                                        name = value;
                                                    }
                                                }
                                                if (key.equals("name")) {
                                                    name = value;
                                                }
                                                if (key.equals("contour_ext") && doContours) {
                                                    isContour = true;
                                                }
                                            }
                                            float[][] wayNodes = way.wayNodes;

                                            if (!isRoad && !isContour) {
                                                continue;
                                            }

                                            String color = "red";
                                            int width = 6;
                                            if (isContour) {
                                                name = "contour";
                                                color = "grey";
                                                width = 2;
                                            }

                                            String trackId = name + "_" + wayNodes[0][0] + "_" + wayNodes[0][1];
                                            if (!waysSet.add(trackId)) {
                                                continue;
                                            }
                                            long logId = log.addGpsLog(dateLong, 0, 0.0, name, width, color, true);

                                            sqliteDatabase.beginTransaction();
                                            try {
                                                long currentTimeMillis = System.currentTimeMillis();

                                                for (float[] wayNode : wayNodes) {
                                                    for (int j = 0; j < wayNode.length - 1; j = j + 2) {
                                                        double lon = wayNode[j] / 1000000.0;
                                                        double lat = wayNode[j + 1] / 1000000.0;
                                                        log.addGpsLogDataPoint(sqliteDatabase, logId, lon, lat, -1, currentTimeMillis);
                                                        currentTimeMillis = currentTimeMillis + 1000l;
                                                    }
                                                }
                                                sqliteDatabase.setTransactionSuccessful();
                                            } catch (Exception e) {
                                                GPLog.error("DAOMAPS", e.getLocalizedMessage(), e);
                                                throw new IOException(e.getLocalizedMessage());
                                            } finally {
                                                sqliteDatabase.endTransaction();
                                            }


                                        }
                                    }

                                    publishProgress(index);
                                }
                            }
                        }
                    } catch (Exception e) {
                        return "ERROR: " + e.getLocalizedMessage();
                    } finally {
                        mapFile.closeFile();
                    }
                    return "";
                }

                @Override
                protected void doUiPostWork(String response) {
                    dispose();
                    if (response.length() != 0) {
                        Utilities.warningDialog(ImportMapsforgeActivity.this, response, null);
                    }

                    finish();
//                    Intent intent = new Intent(getApplicationContext(), MapsSupportService.class);
//                    intent.putExtra(MapsSupportService.REREAD_MAP_REQUEST, true);
//                    startService(intent);
                }
            };
            task.startProgressDialog("Extraction", "Extracting mapsforge data...", false, (int) count);
            task.execute();


        } else {
            Utilities.warningDialog(this, "This tool works only when a mapsforge map is loaded.", null);
        }
    }

}