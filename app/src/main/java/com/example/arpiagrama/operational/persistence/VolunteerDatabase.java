package com.example.arpiagrama.operational.persistence;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {Volunteer.class}, version = 1, exportSchema = false)
public abstract class VolunteerDatabase extends RoomDatabase {

    private static final String DB_NAME = "volunteers.db";
    private static volatile VolunteerDatabase instance;

    public abstract VolunteerDao volunteerDao();

    public static VolunteerDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (VolunteerDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(), VolunteerDatabase.class, DB_NAME)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }
}
