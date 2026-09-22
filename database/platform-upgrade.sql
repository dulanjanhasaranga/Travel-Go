-- MySQL 8 reference: apply only to a backed-up pre-upgrade schema when ddl-auto is disabled.
-- Do not run twice. Hibernate's existing ddl-auto=update applies equivalent additive changes locally.
ALTER TABLE bookings ADD COLUMN request_key VARCHAR(100) NULL,
 ADD COLUMN request_fingerprint VARCHAR(64) NULL,
 ADD CONSTRAINT uk_booking_request_key UNIQUE (request_key);
ALTER TABLE tour_packages ADD COLUMN itinerary TEXT NULL,
 ADD COLUMN excluded_services TEXT NULL, ADD COLUMN traveler_information TEXT NULL;
ALTER TABLE contact_messages ADD COLUMN phone VARCHAR(255) NULL,
 ADD COLUMN inquiry_type VARCHAR(255) NULL, ADD COLUMN preferred_date DATE NULL,
 ADD COLUMN traveler_count INT NULL, ADD COLUMN budget_range VARCHAR(255) NULL,
 ADD COLUMN destination_id BIGINT NULL, ADD COLUMN tour_package_id BIGINT NULL,
 ADD COLUMN internal_notes TEXT NULL, ADD COLUMN version BIGINT DEFAULT 0,
 ADD COLUMN submission_key VARCHAR(64) NULL,
 ADD CONSTRAINT uk_inquiry_submission_key UNIQUE(submission_key),
 ADD CONSTRAINT fk_inquiry_destination FOREIGN KEY(destination_id) REFERENCES destinations(id),
 ADD CONSTRAINT fk_inquiry_package FOREIGN KEY(tour_package_id) REFERENCES tour_packages(id);
ALTER TABLE contact_messages MODIFY COLUMN status ENUM('OPEN','IN_PROGRESS','CONTACTED','RESOLVED','CLOSED') NOT NULL;
CREATE TABLE outbound_emails (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, event_key VARCHAR(120) NOT NULL UNIQUE,
 recipient VARCHAR(150) NOT NULL, subject VARCHAR(255) NOT NULL, html_body TEXT NOT NULL,
 status VARCHAR(255) NOT NULL, created_at DATETIME(6), sent_at DATETIME(6),
 next_attempt_at DATETIME(6), attempts INT NOT NULL
);
