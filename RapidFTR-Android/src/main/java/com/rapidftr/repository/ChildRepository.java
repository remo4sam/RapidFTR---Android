package com.rapidftr.repository;

import android.content.ContentValues;
import android.database.Cursor;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.rapidftr.RapidFtrApplication;
import com.rapidftr.database.Database;
import com.rapidftr.database.DatabaseSession;
import com.rapidftr.forms.FormField;
import com.rapidftr.model.Child;
import com.rapidftr.utils.JSONArrays;
import com.rapidftr.utils.RapidFtrDateTime;
import lombok.Cleanup;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

import static com.rapidftr.database.Database.BooleanColumn;
import static com.rapidftr.database.Database.BooleanColumn.falseValue;
import static com.rapidftr.database.Database.ChildTableColumn.*;
import static com.rapidftr.model.Child.History.HISTORIES;
import static java.lang.String.format;

public class ChildRepository implements Closeable, Repository<Child> {

    protected final String userName;
    protected final DatabaseSession session;

    @Inject
    public ChildRepository(@Named("USER_NAME") String userName, DatabaseSession session) {
        this.userName = userName;
        this.session = session;
    }

    @Override
    public Child get(String id) throws JSONException {
        @Cleanup Cursor cursor = session.rawQuery("SELECT child_json, synced FROM children WHERE id = ?", new String[]{id});
        if (cursor.moveToNext()) {
            return childFrom(cursor);
        } else {
            throw new NullPointerException(id);
        }
    }

    @Override
    public boolean exists(String childId) {
        @Cleanup Cursor cursor = session.rawQuery("SELECT child_json FROM children WHERE id = ?", new String[]{childId == null ? "" : childId});
        return cursor.moveToNext() && cursor.getCount() > 0;
    }

    @Override
    public int size() {
        @Cleanup Cursor cursor = session.rawQuery("SELECT COUNT(1) FROM children WHERE child_owner = ?", new String[]{userName});
        return cursor.moveToNext() ? cursor.getInt(0) : 0;
    }

    public List<Child> getChildrenByOwner() throws JSONException {
        @Cleanup Cursor cursor = session.rawQuery("SELECT child_json, synced FROM children WHERE child_owner = ? ORDER BY id", new String[]{userName});
        return toChildren(cursor);
    }

    @Override
    public ArrayList<String> getRecordIdsByOwner() throws JSONException {
        ArrayList<String> ids = new ArrayList<String>();
        @Cleanup Cursor cursor = session.rawQuery("SELECT _id FROM children WHERE child_owner = ? ", new String[]{userName});
        while (cursor.moveToNext()) {
            ids.add(cursor.getString(0));
        }
        return ids;
    }

    public void deleteChildrenByOwner() throws JSONException {
        session.execSQL("DELETE FROM children WHERE child_owner = '" + userName + "';");
    }

    public List<Child> getMatchingChildren(String searchString, List<FormField> highlightedFields) throws JSONException {
        highlightedFields = (highlightedFields == null) ? Collections.EMPTY_LIST : highlightedFields;
        String query = buildSQLQueryForSearch(searchString, RapidFtrApplication.getApplicationInstance());
        @Cleanup Cursor cursor = session.rawQuery(query, null);
        return filterChildrenWithRegularExpression(cursor, searchString, highlightedFields);
    }

    private String buildSQLQueryForSearch(String searchString, RapidFtrApplication context) throws JSONException {
        StringBuilder queryBuilder = new StringBuilder("SELECT child_json, synced FROM children WHERE (").append(fetchByOwner(context));
        String[] subQueries = searchString.split("\\s+");
        for (int i = 0;i<subQueries.length;i++) {
            queryBuilder.append(String.format("child_json LIKE '%%%s%%' OR id LIKE '%%%s%%'", subQueries[i], subQueries[i]));
            if(i < subQueries.length-1)  {
                queryBuilder.append(" OR ");
            }
        }
        return queryBuilder.append(")").toString();
    }

    private List<Child> filterChildrenWithRegularExpression(Cursor cursor, String filterString, List<FormField> highlightedFields) throws JSONException {
        List<Child> children = new ArrayList<Child>();
        Pattern pattern = buildPatternFromSearchString(filterString);

        while (cursor.moveToNext()) {
            Child child = childFrom(cursor);
            if (pattern.matcher(child.getShortId()).matches()) {
                children.add(child);
            } else {
                for (FormField formField : highlightedFields) {
                    boolean formFieldMatchesPattern = pattern.matcher(child.optString(formField.getId())).matches();
                    if (!children.contains(child) && formFieldMatchesPattern) {
                        children.add(child);
                    }
                }
            }
        }
        return children;
    }

    private Pattern buildPatternFromSearchString(String searchString) {
        String[] splitQuery = searchString.split("\\s+");
        StringBuilder regexBuilder = new StringBuilder();
        for(int i = 0; i < splitQuery.length; i++) {
            regexBuilder.append(String.format(".*(%s)+.*", splitQuery[i]));
            if((i < splitQuery.length-1)){
                regexBuilder.append("|");
            }
        }
        return Pattern.compile(regexBuilder.toString(), Pattern.CASE_INSENSITIVE);
    }

    private String fetchByOwner(RapidFtrApplication context) throws JSONException {
        if (!context.getCurrentUser().isVerified()) {
            return " child_owner = '" + userName + "' AND ";
        } else {
            return "";
        }
    }

    @Override
    public void createOrUpdate(Child child) throws JSONException {
        ContentValues values = new ContentValues();
        if (exists(child.getUniqueId())) {
            addHistory(child);
        }
        child.setLastUpdatedAt(getTimeStamp());
        values.put(Database.ChildTableColumn.owner.getColumnName(), child.getCreatedBy());
        values.put(id.getColumnName(), child.getUniqueId());
        values.put(content.getColumnName(), child.getJsonString());
        values.put(synced.getColumnName(), child.isSynced());
        values.put(created_at.getColumnName(), child.getCreatedAt());
        populateInternalColumns(child, values);
        long id = session.replace(Database.child.getTableName(), null, values);
        if (id <= 0) throw new IllegalArgumentException(id + "");
    }

    private void populateInternalColumns(Child child, ContentValues values) {
        values.put(internal_id.getColumnName(), child.optString("_id"));
        values.put(internal_rev.getColumnName(), child.optString("_rev"));
    }

    private void addHistory(Child child) throws JSONException {
        Child existingChild = get(child.getUniqueId());
        JSONArray existingHistories = (JSONArray) existingChild.opt(HISTORIES);
        List<Child.History> histories = child.changeLogs(existingChild, existingHistories);
        if (histories.size() > 0)
            child.put(HISTORIES, JSONArrays.asJSONObjectArray(histories));
    }

    @Override
    public void update(Child child) throws JSONException {
        ContentValues values = new ContentValues();
        values.put(content.getColumnName(), child.toString());
        values.put(synced.getColumnName(), child.isSynced());
        populateInternalColumns(child, values);
        session.update(Database.child.getTableName(), values, format("%s=?", id.getColumnName()), new String[]{child.getUniqueId()});
    }

    @Override
    public List<Child> toBeSynced() throws JSONException {
        @Cleanup Cursor cursor = session.rawQuery("SELECT child_json, synced FROM children WHERE synced = ?", new String[]{falseValue.getColumnValue()});
        return toChildren(cursor);
    }

    @Override
    public List<Child> currentUsersUnsyncedRecords() throws JSONException {
        @Cleanup Cursor cursor = session.rawQuery("SELECT child_json, synced FROM children WHERE synced = ? AND child_owner = ?", new String[]{falseValue.getColumnValue(), userName});
        return toChildren(cursor);
    }

    @Override // TODO remove this method - we no longer want to work out what to update by comparing _revs
    public HashMap<String, String> getAllIdsAndRevs() throws JSONException {
        HashMap<String, String> idRevs = new HashMap<String, String>();
        @Cleanup Cursor cursor = session.rawQuery("SELECT "
                + Database.ChildTableColumn.internal_id.getColumnName() + ", "
                + Database.ChildTableColumn.internal_rev.getColumnName()
                + " FROM " + Database.child.getTableName(), null);
        while (cursor.moveToNext()) {
            idRevs.put(cursor.getString(0), cursor.getString(1));
        }
        return idRevs;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<Child> toChildren(Cursor cursor) throws JSONException {
        List<Child> children = new ArrayList<Child>();
        while (cursor.moveToNext()) {
            children.add(childFrom(cursor));
        }
        return children;
    }

    private Child childFrom(Cursor cursor) throws JSONException {
        int contentColumnIndex = cursor.getColumnIndex(content.getColumnName());
        int syncedColumnIndex = cursor.getColumnIndex(synced.getColumnName());

        return new Child(cursor.getString(contentColumnIndex), BooleanColumn.from(cursor.getString(syncedColumnIndex)).toBoolean());
    }

    protected String getTimeStamp() {
        return RapidFtrDateTime.now().defaultFormat();
    }

    public List<Child> getChildrenByIds(ArrayList<String> listOfIds) throws JSONException {
        ArrayList<Child> children = new ArrayList<Child>();
        for (String childId : listOfIds) {
            children.add(get(childId));
        }
        return children;
    }

    public List<Child> getAllWithInternalIds(List<String> internalIds) throws JSONException {
        List<Child> children = new ArrayList<Child>();
        for (String internalId : internalIds) {
            Cursor cursor = session.rawQuery("SELECT child_json, synced FROM children WHERE _id = ?", new String[]{internalId});
            if (cursor.moveToNext())
                children.add(childFrom(cursor));
        }
        return children;
    }
}
