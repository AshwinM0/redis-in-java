# Redis in Java

A lightweight, multithreaded implementation of a Redis-like server written in Java. This project implements the Redis Serialization Protocol (RESP) and supports a subset of core Redis commands, allowing interaction via standard Redis clients like `redis-cli`.

## 🚀 Features

-   **RESP Support**: Fully compatible with the Redis Serialization Protocol.
-   **Multithreaded**: Handles multiple concurrent client connections using a thread pool.
-   **In-Memory Storage**: Data is stored efficiently in memory.
-   **Command Support**: Implements a wide range of standard Redis commands.

### Supported Commands

#### String Operations
-   `SET` (with options like EX, PX, NX, XX)
-   `GET`
-   `MSET`, `MGET`
-   `INCR`, `DECR`, `INCRBY`, `DECRBY`, `INCRBYFLOAT`
-   `APPEND`
-   `GETRANGE`, `GETSET`
-   `STRLEN`
-   `SETNX`

#### Hash Operations
-   `HSET`, `HSETNX`
-   `HGET`, `HGETALL`
-   `HDEL`
-   `HEXISTS`
-   `HLEN`

#### Key Management
-   `DEL`
-   `EXISTS`
-   `KEYS`
-   `EXPIRE`, `TTL`
-   `TYPE` (returns `string`, `list`, `hash`, etc.)
-   `FLUSHALL`

#### Server & Connection
-   `PING`
-   `COMMAND`

## 🛠 Prerequisites

-   **Java**: JDK 21 or higher.
-   **Maven**: For building the project.

## 📦 Installation & Usage

1.  **Clone the repository**
    ```bash
    git clone https://github.com/BunsGlazin/redis-in-java.git
    cd redis-in-java
    ```

2.  **Build the project**
    ```bash
    mvn clean package
    ```

3.  **Run the server**
    You can run the generated JAR file:
    ```bash
    java -jar target/redis-1.0-SNAPSHOT.jar
    ```
    *The server listens on port **6379** by default.*

4.  **Connect with a client**
    Open a new terminal and use the standard redis-cli:
    ```bash
    redis-cli
    ```
    Or test a simple command:
    ```bash
    redis-cli PING
    # Output: PONG
    ```

## 🏗 Project Structure

-   `src/main/java/redis`: Core server logic (`RedisServer`, `ClientHandler`, `Database`, `RespParser`).
-   `src/main/java/redis/commands`: Implementation of individual Redis commands.
