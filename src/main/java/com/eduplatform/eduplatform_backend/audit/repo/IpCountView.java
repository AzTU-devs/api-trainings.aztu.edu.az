package com.eduplatform.eduplatform_backend.audit.repo;

/** Projection: an IP address and how many matching events it produced. */
public interface IpCountView {
    String getIp();
    long getCnt();
}
