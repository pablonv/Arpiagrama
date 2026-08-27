package com.example.arpiagrama.operational.persistence;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "volunteers")
public class Volunteer {
    @PrimaryKey(autoGenerate = true)
    private long id;
    private final String name;
    private final String address;
    private final String phone;

    public Volunteer(String name, String address, String phone) {
        this.name = name;
        this.address = address;
        this.phone = phone;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public String getPhone() {
        return phone;
    }
}
