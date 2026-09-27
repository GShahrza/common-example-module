CREATE TABLE product (
    id     BIGSERIAL PRIMARY KEY,
    name   VARCHAR(100)   NOT NULL,
    price  NUMERIC(12, 2) NOT NULL,
    stock  INT            NOT NULL CHECK (stock >= 0)
);

CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    customer    VARCHAR(50)    NOT NULL,
    product_id  BIGINT         NOT NULL REFERENCES product (id),
    quantity    INT            NOT NULL,
    amount      NUMERIC(12, 2) NOT NULL,
    status      VARCHAR(20)    NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_customer ON orders (customer, id);

INSERT INTO product (name, price, stock) VALUES
    ('Kitab', 25, 1000), ('Telefon', 900, 50), ('Noutbuk', 2400, 5), ('Qulaqlıq', 120, 300);

-- 100 000 orders, so that streaming and backpressure have something to work with
INSERT INTO orders (customer, product_id, quantity, amount, status, created_at)
SELECT (ARRAY['aynur', 'rashad', 'kamran', 'nigar', 'leyla', 'elvin'])[1 + i % 6],
       1 + i % 4,
       1 + i % 3,
       (ARRAY[25, 900, 2400, 120])[1 + i % 4] * (1 + i % 3),
       (ARRAY['NEW', 'PAID', 'SHIPPED', 'DELIVERED'])[1 + i % 4],
       now() - (i || ' minutes')::interval
FROM generate_series(1, 100000) AS i;
