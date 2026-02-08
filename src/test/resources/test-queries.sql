-- :name getUserById
SELECT * FROM users WHERE id = {id}

-- :name getUserByStatus
SELECT * FROM users WHERE status = {status}

-- :name getUserByIdAndStatus
SELECT * FROM users WHERE id = {id} AND status = {status}

-- :name getAllUsers
SELECT * FROM users

-- :name insertUser
INSERT INTO users (name, email, status) VALUES ({name}, {email}, {status})

-- :name updateUserStatus
UPDATE users SET status = {status} WHERE id = {id}

-- :name deleteUser
DELETE FROM users WHERE id = {id}

-- :name getUserCount
SELECT COUNT(*) AS user_count FROM users

-- :name getActiveUserCount
SELECT COUNT(*) AS user_count FROM users WHERE status = {status}

-- :name multiLineQuery
SELECT u.name, u.email, o.total
FROM users u
-- join orders
JOIN orders o ON o.user_id = u.id
WHERE u.status = {status}
  AND o.total > {minTotal}
ORDER BY o.total DESC
