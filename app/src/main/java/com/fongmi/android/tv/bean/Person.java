package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.google.gson.annotations.SerializedName;

public class Person {

    @SerializedName("id")
    private int id;

    @SerializedName("name")
    private String name;

    @SerializedName("profile_path")
    private String profilePath;

    @SerializedName("character")
    private String character;

    @SerializedName("department")
    private String department;

    @SerializedName("job")
    private String job;

    @SerializedName("known_for_department")
    private String knownForDepartment;

    public Person() {
    }

    public Person(String name) {
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProfilePath() {
        return profilePath == null ? "" : profilePath;
    }

    public void setProfilePath(String profilePath) {
        this.profilePath = profilePath;
    }

    public String getCharacter() {
        return character == null ? "" : character;
    }

    public void setCharacter(String character) {
        this.character = character;
    }

    public String getDepartment() {
        return department == null ? "" : department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getJob() {
        return job == null ? "" : job;
    }

    public void setJob(String job) {
        this.job = job;
    }

    public String getKnownForDepartment() {
        return knownForDepartment == null ? "" : knownForDepartment;
    }

    public void setKnownForDepartment(String knownForDepartment) {
        this.knownForDepartment = knownForDepartment;
    }

    public boolean hasProfile() {
        return !TextUtils.isEmpty(profilePath);
    }

    public boolean isDirector() {
        return "Directing".equals(department) || "Director".equals(job);
    }
}
