package com.example.arpiagrama.operational.persistence;

import androidx.room.Dao;
import androidx.room.Insert;

@Dao
public interface VolunteerDao {
    @Insert
    void insert(Volunteer volunteer);
}
