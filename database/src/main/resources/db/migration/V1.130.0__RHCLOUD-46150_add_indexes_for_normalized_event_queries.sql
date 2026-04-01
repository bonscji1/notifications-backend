CREATE INDEX ix_event_event_type_id ON event (event_type_id);
CREATE INDEX ix_applications_bundle_id ON applications (bundle_id);
CREATE INDEX ix_event_type_application_id ON event_type (application_id);
CREATE INDEX ix_bundles_display_name ON bundles (display_name);
CREATE INDEX ix_applications_display_name ON applications (display_name);
CREATE INDEX ix_event_type_display_name ON event_type (display_name);
CREATE INDEX ix_event_bundle_id_covering ON event (bundle_id, event_type_id) INCLUDE (id, severity);