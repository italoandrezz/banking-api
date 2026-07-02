CREATE TYPE account_status AS ENUM ('ACTIVE', 'BLOCKED', 'CLOSED');

CREATE TYPE transaction_type AS ENUM ('DEPOSIT', 'WITHDRAW', 'TRANSFER');

CREATE TABLE customers (
                           id UUID PRIMARY KEY,
                           full_name VARCHAR(120) NOT NULL,
                           cpf VARCHAR(11) NOT NULL UNIQUE,
                           email VARCHAR(150) NOT NULL UNIQUE,
                           password VARCHAR(255) NOT NULL,
                           phone VARCHAR(20),
                           birth_date DATE NOT NULL,
                           created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           updated_at TIMESTAMP
);

CREATE TABLE addresses (
                           id UUID PRIMARY KEY,
                           customer_id UUID NOT NULL UNIQUE,
                           street VARCHAR(120) NOT NULL,
                           number VARCHAR(20) NOT NULL,
                           complement VARCHAR(100),
                           district VARCHAR(100) NOT NULL,
                           city VARCHAR(100) NOT NULL,
                           state VARCHAR(2) NOT NULL,
                           zip_code VARCHAR(8) NOT NULL,

                           CONSTRAINT fk_addresses_customers
                               FOREIGN KEY (customer_id)
                                   REFERENCES customers(id)
);

CREATE TABLE accounts (
                          id UUID PRIMARY KEY,
                          customer_id UUID NOT NULL,
                          account_number VARCHAR(20) NOT NULL UNIQUE,
                          agency VARCHAR(10) NOT NULL DEFAULT '0001',
                          balance NUMERIC(15,2) NOT NULL DEFAULT 0.00,
                          status account_status NOT NULL DEFAULT 'ACTIVE',
                          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          updated_at TIMESTAMP,

                          CONSTRAINT fk_accounts_customers
                              FOREIGN KEY (customer_id)
                                  REFERENCES customers(id),

                          CONSTRAINT chk_accounts_balance_non_negative
                              CHECK (balance >= 0)
);

CREATE TABLE transactions (
                              id UUID PRIMARY KEY,
                              account_id UUID NOT NULL,
                              destination_account_id UUID,
                              type transaction_type NOT NULL,
                              amount NUMERIC(15,2) NOT NULL,
                              description VARCHAR(255),
                              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                              CONSTRAINT fk_transactions_accounts
                                  FOREIGN KEY (account_id)
                                      REFERENCES accounts(id),

                              CONSTRAINT fk_transactions_destination_accounts
                                  FOREIGN KEY (destination_account_id)
                                      REFERENCES accounts(id),

                              CONSTRAINT chk_transactions_amount_positive
                                  CHECK (amount > 0)
);