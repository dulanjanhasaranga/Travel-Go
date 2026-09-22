import mysql.connector

try:
    conn = mysql.connector.connect(
        host="localhost",
        user="root",
        password="password",
        database="travelgo"
    )
    cursor = conn.cursor()
    
    # Update Destinations
    cursor.execute("UPDATE destinations SET image = '/images/bangkok_4k.jpg' WHERE city LIKE '%Bangkok%'")
    cursor.execute("UPDATE destinations SET image = '/images/istanbul_4k.jpg' WHERE city LIKE '%Istanbul%'")
    cursor.execute("UPDATE destinations SET image = '/images/maldives_island_escape_4k.jpg' WHERE city LIKE '%Maldives%'")
    cursor.execute("UPDATE destinations SET image = '/images/dubai_desert_4k.jpg' WHERE city LIKE '%Dubai%'")
    
    # Update Packages
    cursor.execute("UPDATE packages SET image = '/images/bangkok_4k.jpg' WHERE name LIKE '%Bangkok%'")
    cursor.execute("UPDATE packages SET image = '/images/istanbul_4k.jpg' WHERE name LIKE '%Istanbul%'")
    cursor.execute("UPDATE packages SET image = '/images/maldives_pkg_4k.jpg' WHERE name LIKE '%Maldives%'")
    cursor.execute("UPDATE packages SET image = '/images/dubai_pkg_4k.jpg' WHERE name LIKE '%Dubai%'")
    
    conn.commit()
    print("Database updated successfully.")
    
except mysql.connector.Error as err:
    print(f"Error: {err}")
    # try without password
    try:
        conn = mysql.connector.connect(
            host="localhost",
            user="root",
            password="",
            database="travelgo"
        )
        cursor = conn.cursor()
        
        # Update Destinations
        cursor.execute("UPDATE destinations SET image = '/images/bangkok_4k.jpg' WHERE city LIKE '%Bangkok%'")
        cursor.execute("UPDATE destinations SET image = '/images/istanbul_4k.jpg' WHERE city LIKE '%Istanbul%'")
        cursor.execute("UPDATE destinations SET image = '/images/maldives_island_escape_4k.jpg' WHERE city LIKE '%Maldives%'")
        cursor.execute("UPDATE destinations SET image = '/images/dubai_desert_4k.jpg' WHERE city LIKE '%Dubai%'")
        
        # Update Packages
        cursor.execute("UPDATE packages SET image = '/images/bangkok_4k.jpg' WHERE name LIKE '%Bangkok%'")
        cursor.execute("UPDATE packages SET image = '/images/istanbul_4k.jpg' WHERE name LIKE '%Istanbul%'")
        cursor.execute("UPDATE packages SET image = '/images/maldives_pkg_4k.jpg' WHERE name LIKE '%Maldives%'")
        cursor.execute("UPDATE packages SET image = '/images/dubai_pkg_4k.jpg' WHERE name LIKE '%Dubai%'")
        
        conn.commit()
        print("Database updated successfully without password.")
    except mysql.connector.Error as err2:
        print(f"Error2: {err2}")
        # try root/root
        try:
            conn = mysql.connector.connect(
                host="localhost",
                user="root",
                password="root",
                database="travelgo"
            )
            cursor = conn.cursor()
            
            # Update Destinations
            cursor.execute("UPDATE destinations SET image = '/images/bangkok_4k.jpg' WHERE city LIKE '%Bangkok%'")
            cursor.execute("UPDATE destinations SET image = '/images/istanbul_4k.jpg' WHERE city LIKE '%Istanbul%'")
            cursor.execute("UPDATE destinations SET image = '/images/maldives_island_escape_4k.jpg' WHERE city LIKE '%Maldives%'")
            cursor.execute("UPDATE destinations SET image = '/images/dubai_desert_4k.jpg' WHERE city LIKE '%Dubai%'")
            
            # Update Packages
            cursor.execute("UPDATE packages SET image = '/images/bangkok_4k.jpg' WHERE name LIKE '%Bangkok%'")
            cursor.execute("UPDATE packages SET image = '/images/istanbul_4k.jpg' WHERE name LIKE '%Istanbul%'")
            cursor.execute("UPDATE packages SET image = '/images/maldives_pkg_4k.jpg' WHERE name LIKE '%Maldives%'")
            cursor.execute("UPDATE packages SET image = '/images/dubai_pkg_4k.jpg' WHERE name LIKE '%Dubai%'")
            
            conn.commit()
            print("Database updated successfully with root/root.")
        except mysql.connector.Error as err3:
            print(f"Error3: {err3}")
