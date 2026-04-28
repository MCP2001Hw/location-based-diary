import os
from flask import Flask, request, jsonify
import psycopg2
from werkzeug.security import generate_password_hash, check_password_hash

app = Flask(__name__)

# =====================================================================
# SECTION 1: DATABASE CONFIGURATION
# =====================================================================

DB_HOST = os.environ.get("DB_HOST", "localhost")
DB_PORT = os.environ.get("DB_PORT", "5432")
DB_NAME = os.environ.get("DB_NAME", "neondb")
DB_USER = os.environ.get("DB_USER", "postgres")
DB_PASS = os.environ.get("DB_PASS", "password")

def get_db_connection():
    return psycopg2.connect(
        host=DB_HOST, 
        port=DB_PORT, 
        dbname=DB_NAME, 
        user=DB_USER, 
        password=DB_PASS,
        sslmode='require'
    )

@app.route('/', methods=['GET'])
def home():
    return jsonify({
        "status": "online", 
        "message": "Welcome to the LBS API! The server is awake and ready."
    }), 200

# =====================================================================
# SECTION 2: USER ACCOUNT & AUTHENTICATION
# =====================================================================

@app.route('/register', methods=['POST'])
def register():
    data = request.json
    username = data.get('username')
    password = data.get('password')

    if not username or not password:
        return jsonify({"error": "Username and password required"}), 400

    hashed_password = generate_password_hash(password)
    conn = get_db_connection()
    cur = conn.cursor()
    try:
        cur.execute(
            "INSERT INTO users (username, password_hash) VALUES (%s, %s) RETURNING user_id",
            (username, hashed_password)
        )
        new_user_id = cur.fetchone()[0]
        conn.commit()
        return jsonify({"message": "User created successfully", "user_id": new_user_id}), 201
    except psycopg2.errors.UniqueViolation:
        conn.rollback()
        return jsonify({"error": "Username already exists"}), 409
    except Exception as e:
        conn.rollback()
        return jsonify({"error": str(e)}), 500
    finally:
        cur.close()
        conn.close()


@app.route('/login', methods=['POST'])
def login():
    data = request.json
    username = data.get('username')
    password = data.get('password')

    if not username or not password:
        return jsonify({"error": "Username and password required"}), 400

    conn = get_db_connection()
    cur = conn.cursor()
    try:
        cur.execute("SELECT user_id, password_hash FROM users WHERE username = %s", (username,))
        user = cur.fetchone()

        if user and check_password_hash(user[1], password):
            return jsonify({"message": "Login successful", "user_id": user[0]}), 200
        else:
            return jsonify({"error": "Invalid username or password"}), 401
    except Exception as e:
        return jsonify({"error": str(e)}), 500
    finally:
        cur.close()
        conn.close()


@app.route('/delete_account/<int:user_id>', methods=['DELETE'])
def delete_account(user_id):
    try:
        conn = get_db_connection()
        cur = conn.cursor()
        cur.execute("DELETE FROM users WHERE user_id = %s;", (user_id,))
        conn.commit()
        cur.close()
        conn.close()

        print(f"\nUser #{user_id} deleted their account.")
        return jsonify({"status": "success", "message": "Account deleted"}), 200
    except Exception as e:
        print("\nDATABASE ERROR:", e)
        return jsonify({"status": "error", "message": str(e)}), 500
    
@app.route('/update_user/<int:user_id>', methods=['PUT'])
def update_user(user_id):
    data = request.json
    new_username = data.get('username')
    new_password = data.get('password')
    
    try:
        conn = get_db_connection()
        cur = conn.cursor()
        
        # Only update the fields the user actually typed something into
        if new_username:
            cur.execute("UPDATE users SET username = %s WHERE user_id = %s;", (new_username, user_id))
        if new_password:
            cur.execute("UPDATE users SET password_hash = %s WHERE user_id = %s;", (generate_password_hash(new_password), user_id))
            
        conn.commit()
        cur.close()
        conn.close()
        return jsonify({"status": "success", "message": "Account updated!"}), 200
    except Exception as e:
        print("\nDATABASE ERROR:", e)
        return jsonify({"status": "error", "message": str(e)}), 500
    
@app.route('/check_consent/<int:user_id>', methods=['GET'])
def check_consent(user_id):
    try:
        conn = get_db_connection()
        cur = conn.cursor()
        cur.execute("SELECT took_consent FROM users WHERE user_id = %s", (user_id,))
        result = cur.fetchone()
        cur.close()
        conn.close()
        
        if result:
            return jsonify({"took_consent": result[0]}), 200
        return jsonify({"error": "User not found"}), 404
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)}), 500

@app.route('/accept_consent/<int:user_id>', methods=['PUT'])
def accept_consent(user_id):
    try:
        conn = get_db_connection()
        cur = conn.cursor()
        cur.execute("UPDATE users SET took_consent = TRUE WHERE user_id = %s", (user_id,))
        conn.commit()
        cur.close()
        conn.close()
        return jsonify({"status": "success"}), 200
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)}), 500


# =====================================================================
# SECTION 3: MISSION / TASK MANAGEMENT (CRUD)
# =====================================================================

@app.route('/get_tasks/<int:user_id>', methods=['GET'])
def get_tasks(user_id):
    try:
        conn = get_db_connection()
        cur = conn.cursor()
        
        # Notice array_agg() and GROUP BY! This bundles multiple locations into a list.
        cur.execute("""
            SELECT 
                t.task_id, t.cycle, t.weekdays, t.time,
                array_agg(p.type_name) AS location_categories, 
                t.description, t.is_active
            FROM tasks t
            LEFT JOIN task_place_types tpt ON t.task_id = tpt.task_id
            LEFT JOIN place_types p ON tpt.place_type_id = p.place_type_id
            WHERE t.user_id = %s
            GROUP BY t.task_id
            ORDER BY t.task_id DESC;
        """, (user_id,))
        
        rows = cur.fetchall()
        tasks = []
        for row in rows:
            # Clean up the array (handles cases where a task has 0 locations)
            raw_locations = row[4]
            clean_locations = [loc for loc in raw_locations if loc is not None] if raw_locations else []

            tasks.append({
                "id": row[0],
                "cycle": row[1] if row[1] else "Once only",
                "weekdays": row[2] if row[2] else [], 
                "time": row[3] if row[3] else "00:00",
                "locationCategories": clean_locations, # <--- NOW A LIST!
                "description": row[5] if row[5] else "",
                "isActive": row[6]
            })

        cur.close()
        conn.close()
        return jsonify(tasks), 200
    except Exception as e:
        print("\nDATABASE ERROR:", e)
        return jsonify({"status": "error", "message": str(e)}), 500


@app.route('/add_task', methods=['POST'])
def add_task():
    data = request.json
    user_id = data.get('user_id')
    description = data.get('description', 'No description provided')
    location_categories = data.get('locationCategories', [])
    clock_time = data.get('time', '00:00')
    cycle = data.get('cycle', 'Once only')
    weekdays = data.get('weekdays', [])

    if not user_id:
        return jsonify({"status": "error", "message": "Missing user_id"}), 400

    try:
        conn = get_db_connection()
        cur = conn.cursor()
        
        # --- NEW: VERIFICATION PHASE ---
        place_type_ids = []
        for loc in location_categories:
            cur.execute("SELECT place_type_id FROM place_types WHERE type_name ILIKE %s;", (loc,))
            place_type_result = cur.fetchone()
            
            if place_type_result:
                place_type_ids.append(place_type_result[0])
            else:
                # If it doesn't exist, abort the whole process!
                cur.close()
                conn.close()
                return jsonify({"status": "error", "message": f"Invalid location type: '{loc}'"}), 400

        # --- EXECUTION PHASE (Only runs if all locations are valid) ---
        cur.execute(
            """
            INSERT INTO tasks (user_id, description, cycle, weekdays, time, is_active) 
            VALUES (%s, %s, %s, %s, %s, TRUE) RETURNING task_id;
            """,
            (user_id, description, cycle, weekdays, clock_time)
        )
        new_task_id = cur.fetchone()[0]

        # We already have the verified IDs, so we just loop and insert!
        for pid in place_type_ids:
            cur.execute(
                "INSERT INTO task_place_types (task_id, place_type_id) VALUES (%s, %s);",
                (new_task_id, pid)
            )
        
        conn.commit()
        cur.close()
        conn.close()
        return jsonify({"status": "success", "message": "Mission saved!"}), 201
    except Exception as e:
        print("\nDATABASE ERROR:", e)
        return jsonify({"status": "error", "message": str(e)}), 500


@app.route('/edit_task/<int:task_id>', methods=['PUT'])
def edit_task(task_id):
    data = request.json
    description = data.get('description', 'No description provided')
    location_categories = data.get('locationCategories', [])
    clock_time = data.get('time', '00:00')
    cycle = data.get('cycle', 'Once only')
    weekdays = data.get('weekdays', [])

    try:
        conn = get_db_connection()
        cur = conn.cursor()

        # --- NEW: VERIFICATION PHASE ---
        place_type_ids = []
        for loc in location_categories:
            cur.execute("SELECT place_type_id FROM place_types WHERE type_name ILIKE %s;", (loc,))
            place_type_result = cur.fetchone()
            
            if place_type_result:
                place_type_ids.append(place_type_result[0])
            else:
                cur.close()
                conn.close()
                return jsonify({"status": "error", "message": f"Invalid location type: '{loc}'"}), 400

        # --- EXECUTION PHASE ---
        cur.execute(
            """
            UPDATE tasks 
            SET description = %s, cycle = %s, weekdays = %s, time = %s 
            WHERE task_id = %s;
            """,
            (description, cycle, weekdays, clock_time, task_id)
        )

        cur.execute("DELETE FROM task_place_types WHERE task_id = %s;", (task_id,))

        for pid in place_type_ids:
            cur.execute(
                "INSERT INTO task_place_types (task_id, place_type_id) VALUES (%s, %s);",
                (task_id, pid)
            )

        conn.commit()
        cur.close()
        conn.close()
        return jsonify({"status": "success", "message": f"Mission {task_id} updated!"}), 200
    except Exception as e:
        print("\nDATABASE ERROR:", e)
        return jsonify({"status": "error", "message": str(e)}), 500


@app.route('/delete_task/<int:task_id>', methods=['DELETE'])
def delete_task(task_id):
    try:
        conn = get_db_connection()
        cur = conn.cursor()
        cur.execute("DELETE FROM task_place_types WHERE task_id = %s;", (task_id,))
        cur.execute("DELETE FROM tasks WHERE task_id = %s;", (task_id,))
        conn.commit()
        cur.close()
        conn.close()
        return jsonify({"status": "success", "message": f"Mission {task_id} deleted"}), 200
    except Exception as e:
        print("\nDATABASE ERROR:", e)
        return jsonify({"status": "error", "message": str(e)}), 500


@app.route('/toggle_active/<int:task_id>', methods=['PUT'])
def toggle_active(task_id):
    data = request.json
    new_status = data.get('isActive', True)

    try:
        conn = get_db_connection()
        cur = conn.cursor()
        cur.execute("UPDATE tasks SET is_active = %s WHERE task_id = %s;", (new_status, task_id))
        conn.commit()
        cur.close()
        conn.close()
        return jsonify({"status": "success", "message": f"Task {task_id} active status updated"}), 200
    except Exception as e:
        print("\nDATABASE ERROR:", e)
        return jsonify({"status": "error", "message": str(e)}), 500


# =====================================================================
# SECTION 4: GPS & SPATIAL ENGINE
# =====================================================================

@app.route('/get_amenities', methods=['GET'])
def get_amenities():
    try:
        conn = get_db_connection()
        cur = conn.cursor()
        cur.execute("SELECT type_name FROM place_types ORDER BY type_name;")
        unique_places = [row[0] for row in cur.fetchall()]
        cur.close()
        conn.close()
        return jsonify(unique_places), 200
    except Exception as e:
        print("\nDATABASE ERROR:", e)
        return jsonify({"status": "error", "message": str(e)}), 500


@app.route('/check_location', methods=['POST'])
def check_location():
    data = request.json
    lat = data.get('lat')
    lon = data.get('lon')
    user_id = data.get('user_id')
    radius = data.get('radius', 200)

    if not lat or not lon or not user_id:
        return jsonify({"status": "error", "message": "Missing lat/lon/user_id"}), 400

    try:
        conn = get_db_connection()
        cur = conn.cursor()

        # 1. Update the user's current live location
        update_query = """
            UPDATE users 
            SET last_location = ST_SetSRID(ST_MakePoint(%s, %s), 4326)::geography, 
            last_updated = CURRENT_TIMESTAMP 
            WHERE user_id = %s;
        """
        cur.execute(update_query, (lon, lat, user_id))
        
        # 2. THE BLUE DOT: Log this coordinate as a normal walking path
        cur.execute("""
            INSERT INTO location_logs (user_id, lat, lon, is_trigger) 
            VALUES (%s, %s, %s, FALSE);
        """, (user_id, lat, lon))
        
        conn.commit()

        # 3. Search for nearby missions
        search_query = """
            SELECT t.task_id, t.description, p.type_name
            FROM tasks t
            JOIN task_place_types tpt ON t.task_id = tpt.task_id
            JOIN place_types p ON tpt.place_type_id = p.place_type_id
            WHERE t.is_active = TRUE
            AND t.user_id = %s
            AND (
                EXISTS (
                    SELECT 1 FROM planet_osm_point pop 
                    WHERE (pop.amenity = REPLACE(LOWER(p.type_name), ' ', '_') 
                        OR pop.shop = REPLACE(LOWER(p.type_name), ' ', '_')
                        OR pop.leisure = REPLACE(LOWER(p.type_name), ' ', '_')
                        OR pop.tourism = REPLACE(LOWER(p.type_name), ' ', '_')
                        OR pop.office = REPLACE(LOWER(p.type_name), ' ', '_'))
                    AND ST_DWithin(pop.way, ST_Transform(ST_SetSRID(ST_MakePoint(%s, %s), 4326), 3857), %s)
                )
                OR
                EXISTS (
                    SELECT 1 FROM planet_osm_polygon poly
                    WHERE (poly.amenity = REPLACE(LOWER(p.type_name), ' ', '_') 
                        OR poly.shop = REPLACE(LOWER(p.type_name), ' ', '_')
                        OR poly.leisure = REPLACE(LOWER(p.type_name), ' ', '_')
                        OR poly.tourism = REPLACE(LOWER(p.type_name), ' ', '_')
                        OR poly.office = REPLACE(LOWER(p.type_name), ' ', '_'))
                    AND ST_DWithin(poly.way, ST_Transform(ST_SetSRID(ST_MakePoint(%s, %s), 4326), 3857), %s)
                )
            )
        """
        cur.execute(search_query, (user_id, lon, lat, radius, lon, lat, radius))
        rows = cur.fetchall()

        matches = []
        for row in rows:
            matches.append({
                "task_id": row[0],
                "description": row[1],
                "location_category": row[2]
            })

        cur.close()
        conn.close()

        if matches:
            print(f"\n🚨 [SERVER] User #{user_id} is near {len(matches)} target zones! Sending to Android to verify time/day rules.")
            
        return jsonify({"status": "success", "matches": matches}), 200

    except Exception as e:
        print("\nDATABASE ERROR:", e)
        return jsonify({"status": "error", "message": str(e)}), 500
    
# =====================================================================
# SECTION 5: BREADCRUMB TRAILS (MAP HISTORY)
# =====================================================================

@app.route('/log_trigger', methods=['POST'])
def log_trigger():
    """ 
    Android calls this ONLY when a notification actually fires.
    It drops a RED DOT on the map.
    """
    data = request.json
    try:
        conn = get_db_connection()
        cur = conn.cursor()
        
        # is_trigger = TRUE means this is a Red Dot!
        cur.execute("""
            INSERT INTO location_logs (user_id, lat, lon, is_trigger) 
            VALUES (%s, %s, %s, TRUE);
        """, (data['user_id'], data['lat'], data['lon']))
        
        conn.commit()
        cur.close()
        conn.close()
        return jsonify({"message": "Red dot trigger logged!"}), 200
    except Exception as e:
        print("\nDATABASE ERROR:", e)
        return jsonify({"status": "error", "message": str(e)}), 500

@app.route('/get_location_history/<int:target_user_id>', methods=['GET'])
def get_location_history(target_user_id):
    """
    Fetches the trail of dots for the map. 
    Strictly blocks strangers from viewing history.
    """
    requester_id = request.args.get('requester_id', type=int)

    try:
        conn = get_db_connection()
        cur = conn.cursor()

        # 1. SECURITY CHECK: Are they looking at a friend's map?
        if requester_id != target_user_id:
            cur.execute("""
                SELECT 1 FROM friends 
                WHERE ((main_user = %s AND sub_user = %s) 
                   OR (main_user = %s AND sub_user = %s))
                   AND friend_status = 'approved' AND share_location = TRUE
            """, (requester_id, target_user_id, target_user_id, requester_id))
            
            if not cur.fetchone():
                return jsonify({"error": "You do not have permission to view this trail."}), 403

        # 2. FETCH THE TRAIL: Get logs from the last 24 hours
        cur.execute("""
            SELECT lat, lon, is_trigger, timestamp 
            FROM location_logs 
            WHERE user_id = %s 
              AND timestamp >= NOW() - INTERVAL '24 hours'
            ORDER BY timestamp ASC;
        """, (target_user_id,))
        
        logs = cur.fetchall()
        
        # Format for Leaflet Map
        history = []
        for row in logs:
            history.append({
                "lat": row[0],
                "lon": row[1],
                "is_trigger": row[2],
                "time": row[3].strftime("%H:%M") if row[3] else ""
            })

        cur.close()
        conn.close()
        return jsonify(history), 200
    except Exception as e:
        print("\nDATABASE ERROR:", e)
        return jsonify({"status": "error", "message": str(e)}), 500

@app.route('/send_friend_request', methods=['POST'])
def send_friend_request():
    data = request.json
    main_user = data.get('user_id') # You are the main user sending it
    friend_username = data.get('friend_username')
    share_location = data.get('share_location', True) # Default to sharing your location
    
    try:
        conn = get_db_connection()
        cur = conn.cursor()
        
        cur.execute("SELECT user_id FROM users WHERE username = %s;", (friend_username,))
        friend = cur.fetchone()
        
        if not friend:
            return jsonify({"status": "error", "message": "User not found!"}), 404
            
        sub_user = friend[0]
        
        if main_user == sub_user:
            return jsonify({"status": "error", "message": "You cannot add yourself!"}), 400
        
        cur.execute("""
            INSERT INTO friends (main_user, sub_user, friend_status, share_location) 
            VALUES (%s, %s, 'pending', %s) 
            ON CONFLICT (main_user, sub_user) 
            DO UPDATE SET friend_status = 'pending', requested_date = CURRENT_TIMESTAMP;
        """, (main_user, sub_user, share_location))
        
        conn.commit()
        cur.close()
        conn.close()
        return jsonify({"status": "success", "message": f"Request sent to {friend_username}!"}), 200
    except Exception as e:
        print("\nDATABASE ERROR:", e)
        return jsonify({"status": "error", "message": str(e)}), 500
    
@app.route('/respond_friend_request', methods=['PUT'])
def respond_friend_request():
    data = request.json
    sub_user = data.get('user_id') # You are the sub_user receiving it
    main_user = data.get('requester_id')
    new_status = data.get('status') # 'approved' or 'denied'

    if new_status not in ['approved', 'denied']:
        return jsonify({"status": "error", "message": "Invalid status"}), 400

    try:
        conn = get_db_connection()
        cur = conn.cursor()
        
        cur.execute("""
            UPDATE friends 
            SET friend_status = %s 
            WHERE main_user = %s AND sub_user = %s;
        """, (new_status, main_user, sub_user))
        
        conn.commit()
        cur.close()
        conn.close()
        return jsonify({"status": "success", "message": f"Request {new_status}!"}), 200
    except Exception as e:
        print("\nDATABASE ERROR:", e)
        return jsonify({"status": "error", "message": str(e)}), 500
    
@app.route('/get_friends_locations/<int:user_id>', methods=['GET'])
def get_friends_locations(user_id):
    try:
        conn = get_db_connection()
        cur = conn.cursor()
        
        # Notice the CASE statement! If share_location is FALSE, distance becomes NULL instantly to protect privacy.
        cur.execute("""
            SELECT 
                friend.user_id,
                friend.username,
                CASE WHEN f.share_location = TRUE THEN ST_Distance(me.last_location, friend.last_location) ELSE NULL END AS distance_in_meters,
                friend.last_updated,
                f.share_location
            FROM friends f
            JOIN users friend ON (friend.user_id = f.main_user OR friend.user_id = f.sub_user) AND friend.user_id != %s
            JOIN users me ON me.user_id = %s
            WHERE (f.main_user = %s OR f.sub_user = %s)
              AND f.friend_status = 'approved';
        """, (user_id, user_id, user_id, user_id))
        
        rows = cur.fetchall()
        friends_data = []
        
        for row in rows:
            friends_data.append({
                "friend_id": row[0],
                "username": row[1],
                "distance_meters": round(row[2], 1) if row[2] is not None else None,
                "last_updated": row[3].strftime("%Y-%m-%d %H:%M:%S") if row[3] is not None else None,
                "share_location": row[4]
            })
            
        cur.close()
        conn.close()
        return jsonify(friends_data), 200
    except Exception as e:
        print("\nDATABASE ERROR:", e)
        return jsonify({"status": "error", "message": str(e)}), 500
    
@app.route('/get_pending_requests/<int:user_id>', methods=['GET'])
def get_pending_requests(user_id):
    try:
        conn = get_db_connection()
        cur = conn.cursor()
        
        # Look for requests where YOU are the sub_user, and status is pending
        cur.execute("""
            SELECT f.main_user, u.username, f.requested_date
            FROM friends f
            JOIN users u ON f.main_user = u.user_id
            WHERE f.sub_user = %s AND f.friend_status = 'pending';
        """, (user_id,))
        
        rows = cur.fetchall()
        requests_data = []
        for row in rows:
            requests_data.append({
                "requester_id": row[0],
                "username": row[1],
                "date": row[2].strftime("%Y-%m-%d %H:%M") if row[2] else ""
            })
            
        cur.close()
        conn.close()
        return jsonify(requests_data), 200
    except Exception as e:
        print("\nDATABASE ERROR:", e)
        return jsonify({"status": "error", "message": str(e)}), 500
    
@app.route('/toggle_friend_share', methods=['PUT'])
def toggle_friend_share():
    data = request.json
    user_id = data.get('user_id')
    friend_id = data.get('friend_id')
    share_location = data.get('share_location')
    
    try:
        conn = get_db_connection()
        cur = conn.cursor()
        
        # Updates the link no matter who sent the original request
        cur.execute("""
            UPDATE friends 
            SET share_location = %s 
            WHERE (main_user = %s AND sub_user = %s) 
               OR (main_user = %s AND sub_user = %s);
        """, (share_location, user_id, friend_id, friend_id, user_id))
        
        conn.commit()
        cur.close()
        conn.close()
        return jsonify({"status": "success"}), 200
    except Exception as e:
        print("\nDATABASE ERROR:", e)
        return jsonify({"status": "error", "message": str(e)}), 500
    
if __name__ == '__main__':
    print("Starting LBS Backend API...")
    app.run(host='0.0.0.0', port=5000, debug=True)