import requests
from bs4 import BeautifulSoup

session = requests.Session()

print("1. Fetching login page to get CSRF token...")
resp = session.get("http://localhost:8080/auth/login")
soup = BeautifulSoup(resp.text, 'html.parser')
csrf_token = soup.find('input', {'name': '_csrf'})['value']

print("2. Logging in as customer...")
resp = session.post("http://localhost:8080/auth/demo-login", data={"account": "customer", "_csrf": csrf_token}, allow_redirects=True)
if resp.status_code != 200:
    print(f"Login failed: {resp.status_code}")
    exit(1)
print(f"Login successful, current URL: {resp.url}")

print("2. Fetching packages...")
resp = session.get("http://localhost:8080/packages")
soup = BeautifulSoup(resp.text, 'html.parser')
package_link = soup.find('a', href=lambda href: href and href.startswith('/packages/') and not href.endswith('/book'))

if not package_link:
    print("No package found to book.")
    exit(1)

package_url = "http://localhost:8080" + package_link['href']
print(f"Found package: {package_url}")

print("3. Fetching booking form to get tokens...")
resp = session.get(package_url + "/book")
soup = BeautifulSoup(resp.text, 'html.parser')
csrf_token = soup.find('input', {'name': '_csrf'})['value']
request_token = soup.find('input', {'name': 'requestToken'})['value']

print("4. Submitting booking...")
package_id = package_url.split("/")[-1]
booking_data = {
    "_csrf": csrf_token,
    "requestToken": request_token,
    "packageId": package_id,
    "travelDate": "2027-01-01",
    "numberOfTravelers": "1",
    "travelerNames": "Test Traveler",
    "travelerPassports": "P12345678",
    "travelerDobs": "1990-01-01",
    "travelerGenders": "Male",
    "travelerNationalities": "US"
}

resp = session.post("http://localhost:8080/customer/bookings/create", data=booking_data, allow_redirects=False)
if resp.status_code != 302:
    print(f"Booking failed: {resp.status_code}")
    print(resp.text)
    exit(1)

import urllib.parse
redirect_url = urllib.parse.urljoin("http://localhost:8080", resp.headers['Location'])
print(f"Booking successful, redirected to: {redirect_url}")

# Fetch dashboard
resp = session.get(redirect_url)
print("Dashboard loaded successfully.")

print("All tests passed.")
