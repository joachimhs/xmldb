-- :name getOrdersByUserId
SELECT * FROM orders WHERE user_id = {userId}

-- :name insertOrder
INSERT INTO orders (user_id, total, status) VALUES ({userId}, {total}, {status})
