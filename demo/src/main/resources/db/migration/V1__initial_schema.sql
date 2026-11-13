-- Fresh production database only. Existing databases need an explicit baseline/import plan.
CREATE TABLE users (
    user_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    phone VARCHAR(15) NOT NULL,
    role ENUM('ADMIN', 'USER') NOT NULL,
    enabled BIT NOT NULL,
    created_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sellers (
    seller_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    phone VARCHAR(15) NOT NULL,
    tax_code VARCHAR(20) UNIQUE,
    created_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE products (
    product_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    stock_quantity INT NOT NULL,
    unit VARCHAR(20) NOT NULL,
    current_unit_price DECIMAL(15,2) NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE user_sessions (
    session_id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6),
    revoked_reason VARCHAR(30),
    ip_address VARCHAR(45),
    user_agent VARCHAR(255),
    INDEX idx_user_sessions_user (user_id),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE refresh_tokens (
    token_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6),
    INDEX idx_refresh_tokens_session (session_id),
    CONSTRAINT fk_tokens_session FOREIGN KEY (session_id) REFERENCES user_sessions(session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE invoices (
    invoice_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    invoice_code VARCHAR(50) NOT NULL UNIQUE,
    total_amount DECIMAL(15,2) NOT NULL,
    buyer_name VARCHAR(100) NOT NULL,
    buyer_phone VARCHAR(15) NOT NULL,
    status VARCHAR(30),
    gpay_bill_id VARCHAR(100),
    request_id VARCHAR(100),
    gpay_trans_id VARCHAR(255),
    virtual_account_number VARCHAR(50),
    virtual_account_bank VARCHAR(20),
    created_at DATETIME(6),
    seller_id BIGINT NOT NULL,
    user_id BIGINT,
    CONSTRAINT fk_invoices_seller FOREIGN KEY (seller_id) REFERENCES sellers(seller_id),
    CONSTRAINT fk_invoices_user FOREIGN KEY (user_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE invoice_details (
    detail_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    invoice_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(15,2) NOT NULL,
    line_total DECIMAL(15,2) NOT NULL,
    status VARCHAR(20),
    CONSTRAINT fk_details_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_id),
    CONSTRAINT fk_details_product FOREIGN KEY (product_id) REFERENCES products(product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
