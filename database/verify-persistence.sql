-- Choose the same schema/port as the application's JDBC URL in Workbench first.
-- Read-only queries. Refresh each result after a form submission and after an app restart.
SELECT DATABASE() AS selected_schema, @@port AS server_port;
SHOW TABLES;
SELECT p.id, p.name, d.city, d.country, c.name AS category, p.base_price,
       p.duration_days, p.max_capacity, p.is_active
FROM tour_packages p JOIN destinations d ON d.id=p.destination_id
JOIN package_categories c ON c.id=p.category_id ORDER BY p.id;
SELECT b.id, b.user_id, b.tour_package_id, p.name, b.travel_date, b.number_of_travelers,
       b.package_unit_price, b.total_package_amount, b.booking_status, b.created_at
FROM bookings b JOIN tour_packages p ON p.id=b.tour_package_id ORDER BY b.id DESC;
SELECT id, booking_id, full_name, date_of_birth FROM travelers ORDER BY id DESC;
SELECT id, booking_id, hotel_id, check_in_date, check_out_date, number_of_rooms,
       number_of_nights, hotel_cost FROM booking_hotels ORDER BY id DESC;
SELECT id, name, email, role_id, is_active FROM users ORDER BY id DESC;
SELECT id, name, destination_id, star_rating, price_per_night, is_active FROM hotels;
SELECT id, booking_id, status, visa_charge, documentation_charge FROM visa_applications;
SELECT id, visa_application_id, document_name, is_verified FROM visa_documents;
SELECT id, booking_id, payment_type, amount, payment_status FROM payments ORDER BY id DESC;
SELECT id, payment_id, amount, status FROM refunds ORDER BY id DESC;
SELECT id, subject, status, created_at FROM contact_messages ORDER BY id DESC;
SELECT status, COUNT(*) AS messages FROM outbound_emails GROUP BY status;
