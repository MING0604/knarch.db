/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package co.touchlab.knarch.db.sqlite

import co.touchlab.knarch.*
import co.touchlab.knarch.db.*
import co.touchlab.knarch.io.*
import co.touchlab.stately.concurrency.AtomicBoolean
import kotlin.test.*
import platform.Foundation.*

class SQLiteDatabaseTest {
    private lateinit var mDatabase:SQLiteDatabase
    private var mDatabaseFile:File?=null
    private var mDatabaseFilePath:String?=null
    private var mDatabaseDir:String?=null
    private var mTransactionListenerOnBeginCalled:AtomicBoolean = AtomicBoolean(false)
    private var mTransactionListenerOnCommitCalled:AtomicBoolean = AtomicBoolean(false)
    private var mTransactionListenerOnRollbackCalled:AtomicBoolean = AtomicBoolean(false)

    private val systemContext = DefaultSystemContext()
    private fun getContext():SystemContext = systemContext

    @BeforeEach
    protected fun setUp() {
//        super.setUp()
        //TODO: IMPLEMENT

        getContext().deleteDatabase(DATABASE_FILE_NAME)
        mDatabaseFilePath = getContext().getDatabasePath(DATABASE_FILE_NAME).path
        mDatabaseFile = getContext().getDatabasePath(DATABASE_FILE_NAME)
        mDatabaseDir = mDatabaseFile?.getParent()
        mDatabaseFile?.getParentFile()?.mkdirs() // directory may not exist
        mDatabase = SQLiteDatabase.openOrCreateDatabase(mDatabaseFilePath!!, null)
        assertNotNull(mDatabase)
        mTransactionListenerOnBeginCalled.value = false
        mTransactionListenerOnCommitCalled.value = false
        mTransactionListenerOnRollbackCalled.value = false
    }

    fun checkMain()
    {
        if(!NSThread.isMainThread())
            throw RuntimeException("Not in main")
    }

    @AfterEach
    protected fun tearDown() {
        closeAndDeleteDatabase()
    }

    private fun closeAndDeleteDatabase() {
        /*try {*/
            mDatabase.close()
            SQLiteDatabase.deleteDatabase(mDatabaseFile!!)
        /*} catch (e: Exception) {
            //Ehh
        }*/
    }

    @Test
    fun testOpenDatabase() {
        val factory = object : SQLiteDatabase.CursorFactory{
            override fun newCursor(db:SQLiteDatabase,
                                   driver:SQLiteCursorDriver, editTable:String?,
                                       query:SQLiteQuery):Cursor{
                return MockSQLiteCursor(db, driver, editTable, query)
            }
        }

        var db = SQLiteDatabase.openDatabase(mDatabaseFilePath!!,
                factory, SQLiteDatabase.CREATE_IF_NECESSARY)
        assertNotNull(db)
        db.close()

        var dbFile = File(mDatabaseDir, "database_test12345678.db")
        dbFile.delete()

        assertFalse(dbFile.exists())
        db = SQLiteDatabase.openOrCreateDatabase(dbFile.getPath(), factory)
        assertNotNull(db)
        db.close()
        dbFile.delete()
        dbFile = File(mDatabaseDir, DATABASE_FILE_NAME)
        db = SQLiteDatabase.openOrCreateDatabase(dbFile.getPath(), factory)
        assertNotNull(db)
        db.close()
        dbFile.delete()
        db = SQLiteDatabase.create(factory)
        assertNotNull(db)
        db.close()
    }

    @Test
    fun testDeleteDatabase() {
        val dbFile = File(mDatabaseDir, "database_test12345678.db")
        val journalFile = File(dbFile.getPath() + "-journal")
        val shmFile = File(dbFile.getPath() + "-shm")
        val walFile = File(dbFile.getPath() + "-wal")
        val mjFile1 = File(dbFile.getPath() + "-mj00000000")
        val mjFile2 = File(dbFile.getPath() + "-mj00000001")
        val innocentFile = File(dbFile.getPath() + "-innocent")
        dbFile.createNewFile()
        journalFile.createNewFile()
        shmFile.createNewFile()
        walFile.createNewFile()
        mjFile1.createNewFile()
        mjFile2.createNewFile()
        innocentFile.createNewFile()
        val deleted = SQLiteDatabase.deleteDatabase(dbFile)
        assertTrue(deleted)
        assertFalse(dbFile.exists())
        assertFalse(journalFile.exists())
        assertFalse(shmFile.exists())
        assertFalse(walFile.exists())
        assertFalse(mjFile1.exists())
        assertFalse(mjFile2.exists())
        assertTrue(innocentFile.exists())
        innocentFile.delete()
        val deletedAgain = SQLiteDatabase.deleteDatabase(dbFile)
        assertFalse(deletedAgain)
    }

    @Test
    fun testTransaction() {
        mDatabase.execSQL("CREATE TABLE test (num INTEGER);")
        mDatabase.execSQL("INSERT INTO test (num) VALUES (0)")
        // test execSQL without any explicit transactions.
        setNum(1)
        assertNum(1)
        // Test a single-level transaction.
        setNum(0)
        assertFalse(mDatabase.inTransaction())
        mDatabase.beginTransaction()
        assertTrue(mDatabase.inTransaction())
        setNum(1)
        mDatabase.setTransactionSuccessful()
        mDatabase.endTransaction()
        assertFalse(mDatabase.inTransaction())
        assertNum(1)
        // Test a rolled-back transaction.
        setNum(0)
        assertFalse(mDatabase.inTransaction())
        mDatabase.beginTransaction()
        setNum(1)
        assertTrue(mDatabase.inTransaction())
        mDatabase.endTransaction()
        assertFalse(mDatabase.inTransaction())
        assertNum(0)
        // it should throw IllegalStateException if we end a non-existent transaction.
        assertThrowsIllegalState(object:Runnable {
            public override fun run() {
                mDatabase.endTransaction()
            }
        })
        // it should throw IllegalStateException if a set a non-existent transaction as clean.
        assertThrowsIllegalState(object:Runnable {
            public override fun run() {
                mDatabase.setTransactionSuccessful()
            }
        })
        mDatabase.beginTransaction()
        mDatabase.setTransactionSuccessful()
        // it should throw IllegalStateException if we mark a transaction as clean twice.
        assertThrowsIllegalState(object:Runnable {
            public override fun run() {
                mDatabase.setTransactionSuccessful()
            }
        })
        // it should throw IllegalStateException if we begin a transaction after marking the
        // parent as clean.
        assertThrowsIllegalState(object:Runnable {
            public override fun run() {
                mDatabase.beginTransaction()
            }
        })
        mDatabase.endTransaction()
        assertFalse(mDatabase.inTransaction())
        // Test a two-level transaction.
        setNum(0)
        mDatabase.beginTransaction()
        assertTrue(mDatabase.inTransaction())
        mDatabase.beginTransaction()
        assertTrue(mDatabase.inTransaction())
        setNum(1)
        mDatabase.setTransactionSuccessful()
        mDatabase.endTransaction()
        assertTrue(mDatabase.inTransaction())
        mDatabase.setTransactionSuccessful()
        mDatabase.endTransaction()
        assertFalse(mDatabase.inTransaction())
        assertNum(1)
        // Test rolling back an inner transaction.
        setNum(0)
        mDatabase.beginTransaction()
        mDatabase.beginTransaction()
        setNum(1)
        mDatabase.endTransaction()
        mDatabase.setTransactionSuccessful()
        mDatabase.endTransaction()
        assertNum(0)
        // Test rolling back an outer transaction.
        setNum(0)
        mDatabase.beginTransaction()
        mDatabase.beginTransaction()
        setNum(1)
        mDatabase.setTransactionSuccessful()
        mDatabase.endTransaction()
        mDatabase.endTransaction()
        assertNum(0)
    }
    private fun setNum(num:Int) {
        mDatabase.execSQL("UPDATE test SET num = " + num)
    }
    private fun assertNum(num:Int) {
        assertEquals(num.toLong(), DatabaseUtils.longForQuery(mDatabase,
                "SELECT num FROM test", null))
    }
    private fun assertThrowsIllegalState(r:Runnable) {
        try
        {
            r.run()
            fail("did not throw expected IllegalStateException")
        }
        catch (e:IllegalStateException) {}
    }

    @Test
    fun testAccessMaximumSize() {
        val curMaximumSize = mDatabase.getMaximumSize()
        // the new maximum size is less than the current size.
        mDatabase.setMaximumSize(curMaximumSize - 1)
        assertEquals(curMaximumSize, mDatabase.getMaximumSize())
        // the new maximum size is more than the current size.
        mDatabase.setMaximumSize(curMaximumSize + 1)
        assertEquals(curMaximumSize + mDatabase.getPageSize(), mDatabase.getMaximumSize())
        assertTrue(mDatabase.getMaximumSize() > curMaximumSize)
    }

    @Test
    fun testAccessPageSize() {
        val databaseFile = File(mDatabaseDir, "database.db")
        if (databaseFile.exists())
        {
            databaseFile.delete()
        }
        var database:SQLiteDatabase? = null
        try
        {
            database = SQLiteDatabase.openOrCreateDatabase(databaseFile.getPath(), null)
            val initialValue = database.getPageSize()
            // check that this does not throw an exception
            // setting a different page size may not be supported after the DB has been created
            database.setPageSize(initialValue)
            assertEquals(initialValue, database.getPageSize())
        }
        finally
        {
            if (database != null)
            {
                database.close()
                databaseFile.delete()
            }
        }
    }

    @Test
    fun testCompileStatement() {
        mDatabase.execSQL(("CREATE TABLE test (_id INTEGER PRIMARY KEY, " + "name TEXT, age INTEGER, address TEXT);"))
        val name = "Mike"
        val age = 21
        val address = "LA"
        // at the beginning, there is no record in the database.
        var cursor = mDatabase.query("test", TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        assertEquals(0, cursor.getCount())
        val sql = "INSERT INTO test (name, age, address) VALUES (?, ?, ?);"
        val insertStatement = mDatabase.compileStatement(sql)
        DatabaseUtils.bindObjectToProgram(insertStatement, 1, name)
        DatabaseUtils.bindObjectToProgram(insertStatement, 2, age)
        DatabaseUtils.bindObjectToProgram(insertStatement, 3, address)
        insertStatement.execute()
        insertStatement.close()
        cursor.close()
        cursor = mDatabase.query("test", TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount())
        cursor.moveToNext()
        assertEquals(name, cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(age, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals(address, cursor.getString(COLUMN_ADDR_INDEX))
        cursor.close()
        val deleteStatement = mDatabase.compileStatement("DELETE FROM test")
        deleteStatement.execute()
        cursor = mDatabase.query("test", null, null, null, null, null, null)
        assertEquals(0, cursor.getCount())

        deleteStatement.close()
        cursor.close()
    }

    @Test
    fun testDelete() {

        mDatabase.execSQL("CREATE TABLE test (_id INTEGER PRIMARY KEY, " + "name TEXT, age INTEGER, address TEXT);")
        mDatabase.execSQL("INSERT INTO test (name, age, address) VALUES ('Mike', 20, 'LA');")
        mDatabase.execSQL("INSERT INTO test (name, age, address) VALUES ('Jack', 30, 'London');")
        mDatabase.execSQL("INSERT INTO test (name, age, address) VALUES ('Jim', 35, 'Chicago');")

        // delete one record.
        var count = mDatabase.delete(TABLE_NAME, "name = 'Mike'", null)
        assertEquals(1, count)
        var cursor = mDatabase.query(TABLE_NAME, TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        // there are 2 records here.
        assertEquals(2, cursor.getCount())
        cursor.moveToFirst()
        assertEquals("Jack", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(30, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("London", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.moveToNext()
        assertEquals("Jim", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(35, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("Chicago", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.close()
        // delete another record.
        count = mDatabase.delete(TABLE_NAME, "name = ?", arrayOf<String>("Jack"))
        assertEquals(1, count)
        cursor = mDatabase.query(TABLE_NAME, TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        // there are 1 records here.
        assertEquals(1, cursor.getCount())
        cursor.moveToFirst()
        assertEquals("Jim", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(35, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("Chicago", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.close()
        mDatabase.execSQL("INSERT INTO test (name, age, address) VALUES ('Mike', 20, 'LA');")
        mDatabase.execSQL("INSERT INTO test (name, age, address) VALUES ('Jack', 30, 'London');")
        // delete all records.
        count = mDatabase.delete(TABLE_NAME, null, null)
        assertEquals(3, count)
        cursor = mDatabase.query(TABLE_NAME, TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        assertEquals(0, cursor.getCount())
        cursor.close()
    }

    @Test
    fun testExecSQL() {
        mDatabase.execSQL(("CREATE TABLE test (_id INTEGER PRIMARY KEY, " + "name TEXT, age INTEGER, address TEXT);"))
        // add a new record.
        mDatabase.execSQL("INSERT INTO test (name, age, address) VALUES ('Mike', 20, 'LA');")
        var cursor = mDatabase.query(TABLE_NAME, TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount())
        cursor.moveToFirst()

        val dbMikeString: String = cursor.getString(COLUMN_NAME_INDEX)
        assertEquals(dbMikeString.length, 4, "Mike is wrong |$dbMikeString|")

        assertEquals("Mike", dbMikeString)
        assertEquals(20, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("LA", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.close()
        // add other new record.
        mDatabase.execSQL("INSERT INTO test (name, age, address) VALUES ('Jack', 30, 'London');")
        cursor = mDatabase.query(TABLE_NAME, TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        assertEquals(2, cursor.getCount())
        cursor.moveToFirst()
        assertEquals("Mike", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(20, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("LA", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.moveToNext()
        assertEquals("Jack", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(30, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("London", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.close()
        // delete a record.
        mDatabase.execSQL("DELETE FROM test WHERE name = ?;", arrayOf<Any?>("Jack"))
        cursor = mDatabase.query(TABLE_NAME, TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount())
        cursor.moveToFirst()
        assertEquals("Mike", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(20, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("LA", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.close()
        // delete a non-exist record.
        mDatabase.execSQL("DELETE FROM test WHERE name = ?;", arrayOf<Any?>("Wrong Name"))
        cursor = mDatabase.query(TABLE_NAME, TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount())
        cursor.moveToFirst()
        assertEquals("Mike", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(20, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("LA", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.close()
        try
        {
            // execSQL can not use for query.
            mDatabase.execSQL("SELECT * FROM test;")
            fail("should throw SQLException.")
        }
        catch (e:SQLException) {}
        // make sure execSQL can't be used to execute more than 1 sql statement at a time
        mDatabase.execSQL(("UPDATE test SET age = 40 WHERE name = 'Mike';" + "UPDATE test SET age = 50 WHERE name = 'Mike';"))
        // age should be updated to 40 not to 50
        cursor = mDatabase.query(TABLE_NAME, TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount())
        cursor.moveToFirst()
        assertEquals("Mike", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(40, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("LA", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.close()
        // make sure sql injection is NOT allowed or has no effect when using query()
        val harmfulQuery = "name = 'Mike';UPDATE test SET age = 50 WHERE name = 'Mike'"
        cursor = mDatabase.query(TABLE_NAME, TEST_PROJECTION, harmfulQuery, null, null, null, null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount())
        cursor.moveToFirst()
        assertEquals("Mike", cursor.getString(COLUMN_NAME_INDEX))
        // row's age column SHOULD NOT be 50
        assertEquals(40, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("LA", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.close()
    }

    interface Runnable{
        fun run()
    }

    private class MockSQLiteCursor(db:SQLiteDatabase, driver:SQLiteCursorDriver,
                                         editTable:String?, query:SQLiteQuery):SQLiteCursor(driver, query)

    @Test
    fun testFindEditTable() {
        var tables = "table1 table2 table3"
        assertEquals("table1", SQLiteDatabase.findEditTable(tables))
        tables = "table1,table2,table3"
        assertEquals("table1", SQLiteDatabase.findEditTable(tables))
        tables = "table1"
        assertEquals("table1", SQLiteDatabase.findEditTable(tables))
        try
        {
            SQLiteDatabase.findEditTable("")
            fail("should throw IllegalStateException.")
        }
        catch (e:IllegalStateException) {}
    }

    @Test
    fun testGetPath() {
        assertEquals(mDatabaseFilePath, mDatabase.getPath())
    }

    @Test
    fun testAccessVersion() {
        mDatabase.setVersion(1)
        assertEquals(1, mDatabase.getVersion())
        mDatabase.setVersion(3)
        assertEquals(3, mDatabase.getVersion())
    }

    @Test
    fun testInsert() {
        mDatabase.execSQL(("CREATE TABLE test (_id INTEGER PRIMARY KEY, " + "name TEXT, age INTEGER, address TEXT);"))
        var values = ContentValues()
        values.put("name", "Jack")
        values.put("age", 20)
        values.put("address", "LA")
        mDatabase.insert(TABLE_NAME, "name", values)
        var cursor = mDatabase.query(TABLE_NAME, TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount())
        cursor.moveToFirst()
        assertEquals("Jack", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(20, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("LA", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.close()
        mDatabase.insert(TABLE_NAME, "name", null)
        cursor = mDatabase.query(TABLE_NAME, TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        assertEquals(2, cursor.getCount())
        cursor.moveToFirst()
        assertEquals("Jack", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(20, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("LA", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.moveToNext()
        assertNull(cursor.getString(COLUMN_NAME_INDEX))
        cursor.close()
        values = ContentValues()
        values.put("Wrong Key", "Wrong value")
        mDatabase.insert(TABLE_NAME, "name", values)
        // there are still 2 records.
        cursor = mDatabase.query(TABLE_NAME, TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        assertEquals(2, cursor.getCount())
        cursor.close()
        // delete all record.
        mDatabase.execSQL("DELETE FROM test;")
        values = ContentValues()
        values.put("name", "Mike")
        values.put("age", 30)
        values.put("address", "London")
        mDatabase.insertOrThrow(TABLE_NAME, "name", values)
        cursor = mDatabase.query(TABLE_NAME, TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount())
        cursor.moveToFirst()
        assertEquals("Mike", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(30, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("London", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.close()
        mDatabase.insertOrThrow(TABLE_NAME, "name", null)
        cursor = mDatabase.query(TABLE_NAME, TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        assertEquals(2, cursor.getCount())
        cursor.moveToFirst()
        assertEquals("Mike", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(30, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("London", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.moveToNext()
        assertNull(cursor.getString(COLUMN_NAME_INDEX))
        cursor.close()
        values = ContentValues()
        values.put("Wrong Key", "Wrong value")
        try
        {
            mDatabase.insertOrThrow(TABLE_NAME, "name", values)
            fail("should throw SQLException.")
        }
        catch (e:SQLException) {}
    }

    @Test
    fun testIsOpen() {
        assertTrue(mDatabase.isOpen())
        mDatabase.close()
        assertFalse(mDatabase.isOpen())
    }

    @Test
    fun testIsReadOnly() {
        assertFalse(mDatabase.isReadOnly())
        var database:SQLiteDatabase? = null
        try
        {
            database = SQLiteDatabase.openDatabase(mDatabaseFilePath!!, null,
                    SQLiteDatabase.OPEN_READONLY)
            assertTrue(database.isReadOnly())
        }
        finally
        {
            if (database != null)
            {
                database.close()
            }
        }
    }

    @Test
    fun testReleaseMemory() {
        SQLiteDatabase.releaseMemory()
    }

    @Test
    fun testQuery() {
        mDatabase.execSQL(("CREATE TABLE employee (_id INTEGER PRIMARY KEY, " + "name TEXT, month INTEGER, salary INTEGER);"))
        mDatabase.execSQL(("INSERT INTO employee (name, month, salary) " + "VALUES ('Mike', '1', '1000');"))
        mDatabase.execSQL(("INSERT INTO employee (name, month, salary) " + "VALUES ('Mike', '2', '3000');"))
        mDatabase.execSQL(("INSERT INTO employee (name, month, salary) " + "VALUES ('jack', '1', '2000');"))
        mDatabase.execSQL(("INSERT INTO employee (name, month, salary) " + "VALUES ('jack', '3', '1500');"))
        mDatabase.execSQL(("INSERT INTO employee (name, month, salary) " + "VALUES ('Jim', '1', '1000');"))
        mDatabase.execSQL(("INSERT INTO employee (name, month, salary) " + "VALUES ('Jim', '3', '3500');"))
        var cursor = mDatabase.query(true, "employee", arrayOf<String>("name", "sum(salary)"), null, null, "name", "sum(salary)>1000", "name", null)
        assertNotNull(cursor)
        assertEquals(3, cursor.getCount())
        val COLUMN_NAME_INDEX = 0
        val COLUMN_SALARY_INDEX = 1
        cursor.moveToFirst()
        assertEquals("Jim", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(4500, cursor.getInt(COLUMN_SALARY_INDEX))
        cursor.moveToNext()
        assertEquals("Mike", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(4000, cursor.getInt(COLUMN_SALARY_INDEX))
        cursor.moveToNext()
        assertEquals("jack", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(3500, cursor.getInt(COLUMN_SALARY_INDEX))
        cursor.close()
        val factory = object:SQLiteDatabase.CursorFactory {
            override fun newCursor(db:SQLiteDatabase,
                                   driver:SQLiteCursorDriver, editTable:String?,
                                   query:SQLiteQuery):Cursor{
            return MockSQLiteCursor(db, driver, editTable, query)
            }
        }
        cursor = mDatabase.queryWithFactory(factory, true, "employee",
                arrayOf<String>("name", "sum(salary)"), null, null, "name", "sum(salary) > 1000", "name", null)
        assertNotNull(cursor)
        assertTrue(cursor is MockSQLiteCursor)
        cursor.moveToFirst()
        assertEquals("Jim", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(4500, cursor.getInt(COLUMN_SALARY_INDEX))
        cursor.moveToNext()
        assertEquals("Mike", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(4000, cursor.getInt(COLUMN_SALARY_INDEX))
        cursor.moveToNext()
        assertEquals("jack", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(3500, cursor.getInt(COLUMN_SALARY_INDEX))
        cursor.close()
        cursor = mDatabase.query("employee", arrayOf<String>("name", "sum(salary)"), null, null, "name", "sum(salary) <= 4000", "name")
        assertNotNull(cursor)
        assertEquals(2, cursor.getCount())
        cursor.moveToFirst()
        assertEquals("Mike", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(4000, cursor.getInt(COLUMN_SALARY_INDEX))
        cursor.moveToNext()
        assertEquals("jack", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(3500, cursor.getInt(COLUMN_SALARY_INDEX))
        cursor.close()
        cursor = mDatabase.query("employee", arrayOf<String>("name", "sum(salary)"), null, null, "name", "sum(salary) > 1000", "name", "2")
        assertNotNull(cursor)
        assertEquals(2, cursor.getCount())
        cursor.moveToFirst()
        assertEquals("Jim", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(4500, cursor.getInt(COLUMN_SALARY_INDEX))
        cursor.moveToNext()
        assertEquals("Mike", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(4000, cursor.getInt(COLUMN_SALARY_INDEX))
        cursor.close()
        val sql = "SELECT name, month FROM employee WHERE salary > ?;"
        cursor = mDatabase.rawQuery(sql, arrayOf<String>("2000"))
        assertNotNull(cursor)
        assertEquals(2, cursor.getCount())
        val COLUMN_MONTH_INDEX = 1
        cursor.moveToFirst()
        assertEquals("Mike", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(2, cursor.getInt(COLUMN_MONTH_INDEX))
        cursor.moveToNext()
        assertEquals("Jim", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(3, cursor.getInt(COLUMN_MONTH_INDEX))
        cursor.close()
        cursor = mDatabase.rawQueryWithFactory(factory, sql, arrayOf<String>("2000"), null)
        assertNotNull(cursor)
        assertEquals(2, cursor.getCount())
        assertTrue(cursor is MockSQLiteCursor)
        cursor.moveToFirst()
        assertEquals("Mike", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(2, cursor.getInt(COLUMN_MONTH_INDEX))
        cursor.moveToNext()
        assertEquals("Jim", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(3, cursor.getInt(COLUMN_MONTH_INDEX))
        cursor.close()
    }

    @Test
    fun testReplace() {
        mDatabase.execSQL(("CREATE TABLE test (_id INTEGER PRIMARY KEY, " + "name TEXT, age INTEGER, address TEXT);"))
        var values = ContentValues()
        values.put("name", "Jack")
        values.put("age", 20)
        values.put("address", "LA")
        mDatabase.replace(TABLE_NAME, "name", values)
        var cursor = mDatabase.query(TABLE_NAME, TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount())
        cursor.moveToFirst()
        val id = cursor.getInt(COLUMN_ID_INDEX)
        assertEquals("Jack", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(20, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("LA", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.close()
        values = ContentValues()
        values.put("_id", id)
        values.put("name", "Mike")
        values.put("age", 40)
        values.put("address", "London")
        mDatabase.replace(TABLE_NAME, "name", values)
        cursor = mDatabase.query(TABLE_NAME, TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount()) // there is still ONLY 1 record.
        cursor.moveToFirst()
        assertEquals("Mike", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(40, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("London", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.close()
        values = ContentValues()
        values.put("name", "Jack")
        values.put("age", 20)
        values.put("address", "LA")
        mDatabase.replaceOrThrow(TABLE_NAME, "name", values)
        cursor = mDatabase.query(TABLE_NAME, TEST_PROJECTION, null, null, null, null, null)
        assertNotNull(cursor)
        assertEquals(2, cursor.getCount())
        cursor.moveToFirst()
        assertEquals("Mike", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(40, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("London", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.moveToNext()
        assertEquals("Jack", cursor.getString(COLUMN_NAME_INDEX))
        assertEquals(20, cursor.getInt(COLUMN_AGE_INDEX))
        assertEquals("LA", cursor.getString(COLUMN_ADDR_INDEX))
        cursor.close()
        values = ContentValues()
        values.put("Wrong Key", "Wrong value")
        try
        {
            mDatabase.replaceOrThrow(TABLE_NAME, "name", values)
            fail("should throw SQLException.")
        }
        catch (e:SQLException) {}
    }

    @Test
    fun testUpdate() {
        mDatabase.execSQL("CREATE TABLE test (_id INTEGER PRIMARY KEY, data TEXT);")
        mDatabase.execSQL("INSERT INTO test (data) VALUES ('string1');")
        mDatabase.execSQL("INSERT INTO test (data) VALUES ('string2');")
        mDatabase.execSQL("INSERT INTO test (data) VALUES ('string3');")
        var updatedString = "this is an updated test"
        var values = ContentValues(1)
        values.put("data", updatedString)
        assertEquals(1, mDatabase.update("test", values, "_id=1", null))
        var cursor = mDatabase.query("test", null, "_id=1", null, null, null, null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount())
        cursor.moveToFirst()
        var value = cursor.getString(cursor.getColumnIndexOrThrow("data"))
        assertEquals(updatedString, value)
        cursor.close()
    }

    @Test
    fun testNeedUpgrade() {
        mDatabase.setVersion(0)
        assertTrue(mDatabase.needUpgrade(1))
        mDatabase.setVersion(1)
        assertFalse(mDatabase.needUpgrade(1))
    }

    @Test
    fun testTransactionWithSQLiteTransactionListener() {
        mDatabase.execSQL("CREATE TABLE test (num INTEGER);")
        mDatabase.execSQL("INSERT INTO test (num) VALUES (0)")
        assertEquals(mTransactionListenerOnBeginCalled.value, false)
        assertEquals(mTransactionListenerOnCommitCalled.value, false)
        assertEquals(mTransactionListenerOnRollbackCalled.value, false)
        mDatabase.beginTransactionWithListener(TestSQLiteTransactionListener())
        // Assert that the transcation has started
        assertEquals(mTransactionListenerOnBeginCalled.value, true)
        assertEquals(mTransactionListenerOnCommitCalled.value, false)
        assertEquals(mTransactionListenerOnRollbackCalled.value, false)
        setNum(1)
        // State shouldn't have changed
        assertEquals(mTransactionListenerOnBeginCalled.value, true)
        assertEquals(mTransactionListenerOnCommitCalled.value, false)
        assertEquals(mTransactionListenerOnRollbackCalled.value, false)
        // commit the transaction
        mDatabase.setTransactionSuccessful()
        mDatabase.endTransaction()
        // the listener should have been told that commit was called
        assertEquals(mTransactionListenerOnBeginCalled.value, true)
        assertEquals(mTransactionListenerOnCommitCalled.value, true)
        assertEquals(mTransactionListenerOnRollbackCalled.value, false)
    }

    @Test
    fun testRollbackTransactionWithSQLiteTransactionListener() {
        mDatabase.execSQL("CREATE TABLE test (num INTEGER);")
        mDatabase.execSQL("INSERT INTO test (num) VALUES (0)")
        assertEquals(mTransactionListenerOnBeginCalled.value, false)
        assertEquals(mTransactionListenerOnCommitCalled.value, false)
        assertEquals(mTransactionListenerOnRollbackCalled.value, false)
        mDatabase.beginTransactionWithListener(TestSQLiteTransactionListener())
        // Assert that the transcation has started
        assertEquals(mTransactionListenerOnBeginCalled.value, true)
        assertEquals(mTransactionListenerOnCommitCalled.value, false)
        assertEquals(mTransactionListenerOnRollbackCalled.value, false)
        setNum(1)
        // State shouldn't have changed
        assertEquals(mTransactionListenerOnBeginCalled.value, true)
        assertEquals(mTransactionListenerOnCommitCalled.value, false)
        assertEquals(mTransactionListenerOnRollbackCalled.value, false)
        // commit the transaction
        mDatabase.endTransaction()
        // the listener should have been told that commit was called
        assertEquals(mTransactionListenerOnBeginCalled.value, true)
        assertEquals(mTransactionListenerOnCommitCalled.value, false)
        assertEquals(mTransactionListenerOnRollbackCalled.value, true)
    }

    inner class TestSQLiteTransactionListener:SQLiteTransactionListener {
        override fun onBegin() {
            mTransactionListenerOnBeginCalled.value = true
        }
        override fun onCommit() {
            mTransactionListenerOnCommitCalled.value = true
        }
        override fun onRollback() {
            mTransactionListenerOnRollbackCalled.value = true
        }
    }


    @Test
    fun testGroupConcat() {
        mDatabase.execSQL("CREATE TABLE test (i INT, j TEXT);")
        // insert 2 rows
        val sql = "INSERT INTO test (i) VALUES (?);"
        val insertStatement = mDatabase.compileStatement(sql)
        DatabaseUtils.bindObjectToProgram(insertStatement, 1, 1)
        insertStatement.execute()
        DatabaseUtils.bindObjectToProgram(insertStatement, 1, 2)
        insertStatement.execute()
        insertStatement.close()
        // make sure there are 2 rows in the table
        var cursor = mDatabase.rawQuery("SELECT count(*) FROM test", null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount())
        cursor.moveToNext()
        assertEquals(2, cursor.getInt(0))
        cursor.close()
        // concatenate column j from all the rows. should return NULL
        cursor = mDatabase.rawQuery("SELECT group_concat(j, ' ') FROM test", null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount())
        cursor.moveToNext()
        assertNull(cursor.getString(0))
        cursor.close()
        // drop the table
        mDatabase.execSQL("DROP TABLE test;")
        // should get no exceptions
    }

    @Test
    fun testSchemaChanges() {
        mDatabase.execSQL("CREATE TABLE test (i INT, j INT);")
        // at the beginning, there is no record in the database.
        var cursor = mDatabase.rawQuery("SELECT * FROM test", null)
        assertNotNull(cursor)
        assertEquals(0, cursor.getCount())
        cursor.close()
        var sql = "INSERT INTO test VALUES (?, ?);"
        var insertStatement = mDatabase.compileStatement(sql)
        DatabaseUtils.bindObjectToProgram(insertStatement, 1, 1)
        DatabaseUtils.bindObjectToProgram(insertStatement, 2, 2)
        insertStatement.execute()
        insertStatement.close()
        // read the data from the table and make sure it is correct
        cursor = mDatabase.rawQuery("SELECT i,j FROM test", null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount())
        cursor.moveToNext()
        assertEquals(1, cursor.getInt(0))
        assertEquals(2, cursor.getInt(1))
        cursor.close()
        // alter the table and execute another statement
        mDatabase.execSQL("ALTER TABLE test ADD COLUMN k int;")
        sql = "INSERT INTO test VALUES (?, ?, ?);"
        insertStatement = mDatabase.compileStatement(sql)
        DatabaseUtils.bindObjectToProgram(insertStatement, 1, 3)
        DatabaseUtils.bindObjectToProgram(insertStatement, 2, 4)
        DatabaseUtils.bindObjectToProgram(insertStatement, 3, 5)
        insertStatement.execute()
        insertStatement.close()
        // read the data from the table and make sure it is correct
        cursor = mDatabase.rawQuery("SELECT i,j,k FROM test", null)
        assertNotNull(cursor)
        assertEquals(2, cursor.getCount())
        cursor.moveToNext()
        assertEquals(1, cursor.getInt(0))
        assertEquals(2, cursor.getInt(1))
        assertNull(cursor.getString(2))
        cursor.moveToNext()
        assertEquals(3, cursor.getInt(0))
        assertEquals(4, cursor.getInt(1))
        assertEquals(5, cursor.getInt(2))
        cursor.close()
        // make sure the old statement - which should *try to reuse* cached query plan -
        // still works
        cursor = mDatabase.rawQuery("SELECT i,j FROM test", null)
        assertNotNull(cursor)
        assertEquals(2, cursor.getCount())
        cursor.moveToNext()
        assertEquals(1, cursor.getInt(0))
        assertEquals(2, cursor.getInt(1))
        cursor.moveToNext()
        assertEquals(3, cursor.getInt(0))
        assertEquals(4, cursor.getInt(1))
        cursor.close()
        val deleteStatement = mDatabase.compileStatement("DELETE FROM test")
        deleteStatement.execute()
        deleteStatement.close()
    }

    @Test
    fun testSchemaChangesNewTable() {
        mDatabase.execSQL("CREATE TABLE test (i INT, j INT);")
        // at the beginning, there is no record in the database.
        var cursor = mDatabase.rawQuery("SELECT * FROM test", null)
        assertNotNull(cursor)
        assertEquals(0, cursor.getCount())
        cursor.close()
        var sql = "INSERT INTO test VALUES (?, ?);"
        var insertStatement = mDatabase.compileStatement(sql)
        DatabaseUtils.bindObjectToProgram(insertStatement, 1, 1)
        DatabaseUtils.bindObjectToProgram(insertStatement, 2, 2)
        insertStatement.execute()
        insertStatement.close()
        // read the data from the table and make sure it is correct
        cursor = mDatabase.rawQuery("SELECT i,j FROM test", null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount())
        cursor.moveToNext()
        assertEquals(1, cursor.getInt(0))
        assertEquals(2, cursor.getInt(1))
        cursor.close()
        // alter the table and execute another statement
        mDatabase.execSQL("CREATE TABLE test_new (i INT, j INT, k INT);")
        sql = "INSERT INTO test_new VALUES (?, ?, ?);"
        insertStatement = mDatabase.compileStatement(sql)
        DatabaseUtils.bindObjectToProgram(insertStatement, 1, 3)
        DatabaseUtils.bindObjectToProgram(insertStatement, 2, 4)
        DatabaseUtils.bindObjectToProgram(insertStatement, 3, 5)
        insertStatement.execute()
        insertStatement.close()
        // read the data from the table and make sure it is correct
        cursor = mDatabase.rawQuery("SELECT i,j,k FROM test_new", null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount())
        cursor.moveToNext()
        assertEquals(3, cursor.getInt(0))
        assertEquals(4, cursor.getInt(1))
        assertEquals(5, cursor.getInt(2))
        cursor.close()
        // make sure the old statement - which should *try to reuse* cached query plan -
        // still works
        cursor = mDatabase.rawQuery("SELECT i,j FROM test", null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount())
        cursor.moveToNext()
        assertEquals(1, cursor.getInt(0))
        assertEquals(2, cursor.getInt(1))
        cursor.close()
        val deleteStatement = mDatabase.compileStatement("DELETE FROM test")
        deleteStatement.execute()
        deleteStatement.close()
        val deleteStatement2 = mDatabase.compileStatement("DELETE FROM test_new")
        deleteStatement2.execute()
        deleteStatement2.close()
    }

    @Test
    fun testSchemaChangesDropTable() {
        mDatabase.execSQL("CREATE TABLE test (i INT, j INT);")
        // at the beginning, there is no record in the database.
        var cursor = mDatabase.rawQuery("SELECT * FROM test", null)
        assertNotNull(cursor)
        assertEquals(0, cursor.getCount())
        cursor.close()
        val sql = "INSERT INTO test VALUES (?, ?);"
        val insertStatement = mDatabase.compileStatement(sql)
        DatabaseUtils.bindObjectToProgram(insertStatement, 1, 1)
        DatabaseUtils.bindObjectToProgram(insertStatement, 2, 2)
        insertStatement.execute()
        insertStatement.close()
        // read the data from the table and make sure it is correct
        cursor = mDatabase.rawQuery("SELECT i,j FROM test", null)
        assertNotNull(cursor)
        assertEquals(1, cursor.getCount())
        cursor.moveToNext()
        assertEquals(1, cursor.getInt(0))
        assertEquals(2, cursor.getInt(1))
    }

    @Test
    fun testEnableAndDisableForeignKeys() {
        println("testEnableAndDisableForeignKeys A")
        // Initially off.
        assertEquals(0, DatabaseUtils.longForQuery(mDatabase, "PRAGMA foreign_keys", null), "Initially off")
        // Enable foreign keys.
        println("testEnableAndDisableForeignKeys B")
        mDatabase.setForeignKeyConstraintsEnabled(true)
        println("testEnableAndDisableForeignKeys C")
        assertEquals(1, DatabaseUtils.longForQuery(mDatabase, "PRAGMA foreign_keys", null), "Enable foreign keys")
        // Disable foreign keys.
        println("testEnableAndDisableForeignKeys D")
        mDatabase.setForeignKeyConstraintsEnabled(false)
        println("testEnableAndDisableForeignKeys E")
        assertEquals(0, DatabaseUtils.longForQuery(mDatabase, "PRAGMA foreign_keys", null), "Disable foreign keys")
        // Cannot configure foreign keys if there are transactions in progress.
        mDatabase.beginTransaction()
        try
        {
            mDatabase.setForeignKeyConstraintsEnabled(true)
            fail("Expected IllegalStateException (Cannot configure foreign keys if there are transactions in progress)")
        }
        catch (ex:IllegalStateException) {
            // expected
        }
        println("testEnableAndDisableForeignKeys F")
        assertEquals(0, DatabaseUtils.longForQuery(mDatabase, "PRAGMA foreign_keys", null), "After IllegalStateException")
        mDatabase.endTransaction()
        // Enable foreign keys should work again after transaction complete.
        println("testEnableAndDisableForeignKeys G")
        mDatabase.setForeignKeyConstraintsEnabled(true)
        assertEquals(1, DatabaseUtils.longForQuery(mDatabase, "PRAGMA foreign_keys", null), "Enable foreign keys should work again after transaction complete")
    }

    /*

    *//**
     * With sqlite's write-ahead-logging (WAL) enabled, readers get old version of data
     * from the table that a writer is modifying at the same time.
     * <p>
     * This method does the following to test this sqlite3 feature
     * <ol>
     * <li>creates a table in the database and populates it with 5 rows of data</li>
     * <li>do "select count(*) from this_table" and expect to receive 5</li>
     * <li>start a writer thread who BEGINs a transaction, INSERTs a single row
     * into this_table</li>
     * <li>writer stops the transaction at this point, kicks off a reader thread - which will
     * do the above SELECT query: "select count(*) from this_table"</li>
     * <li>this query should return value 5 - because writer is still in transaction and
     * sqlite returns OLD version of the data</li>
     * <li>writer ends the transaction, thus making the extra row now visible to everyone</li>
     * <li>reader is kicked off again to do the same query. this time query should
     * return value = 6 which includes the newly inserted row into this_table.</li>
     *</p>
     * @throws InterruptedException
     *//*
    @LargeTest
    @Throws(InterruptedException::class)
    fun testReaderGetsOldVersionOfDataWhenWriterIsInXact() {
        // redo setup to create WAL enabled database
        closeAndDeleteDatabase()
        mDatabase = SQLiteDatabase.openOrCreateDatabase(mDatabaseFile.getPath(), null, null)
        val rslt = mDatabase.enableWriteAheadLogging()
        assertTrue(rslt)
        assertNotNull(mDatabase)
        // create a new table and insert 5 records into it.
        mDatabase.execSQL("CREATE TABLE t1 (i int, j int);")
        mDatabase.beginTransaction()
        for (i in 0..4)
        {
            mDatabase.execSQL("insert into t1 values(?,?);", arrayOf<String>(i + "", i + ""))
        }
        mDatabase.setTransactionSuccessful()
        mDatabase.endTransaction()
        // make sure a reader can read the above data
        val r1 = ReaderQueryingData(5)
        r1.start()
        Thread.yield()
        try
        {
            r1.join()
        }
        catch (e:Exception) {}
        val w = WriterDoingSingleTransaction()
        w.start()
        w.join()
    }
    private inner class WriterDoingSingleTransaction:Thread() {
        public override fun run() {
            // start a transaction
            mDatabase.beginTransactionNonExclusive()
            mDatabase.execSQL("insert into t1 values(?,?);", arrayOf<String>("11", "11"))
            assertTrue(mDatabase.isOpen())
            // while the writer is in a transaction, start a reader and make sure it can still
            // read 5 rows of data (= old data prior to the current transaction)
            val r1 = ReaderQueryingData(5)
            r1.start()
            try
            {
                r1.join()
            }
            catch (e:Exception) {}
            // now, have the writer do the select count(*)
            // it should execute on the same connection as this transaction
            // and count(*) should reflect the newly inserted row
            val l = DatabaseUtils.longForQuery(mDatabase, "select count(*) from t1", null)
            assertEquals(6, l.toInt())
            // end transaction
            mDatabase.setTransactionSuccessful()
            mDatabase.endTransaction()
            // reader should now be able to read 6 rows = new data AFTER this transaction
            r1 = ReaderQueryingData(6)
            r1.start()
            try
            {
                r1.join()
            }
            catch (e:Exception) {}
        }
    }
    private inner class ReaderQueryingData*//**
     * constructor with a param to indicate the number of rows expected to be read
     *//*
    (count:Int):Thread() {
        private val count:Int = 0
        init{
            this.count = count
        }
        public override fun run() {
            val l = DatabaseUtils.longForQuery(mDatabase, "select count(*) from t1", null)
            assertEquals(count, l.toInt())
        }
    }
    fun testExceptionsFromEnableWriteAheadLogging() {
        // attach a database
        // redo setup to create WAL enabled database
        closeAndDeleteDatabase()
        mDatabase = SQLiteDatabase.openOrCreateDatabase(mDatabaseFile.getPath(), null, null)
        // attach a database and call enableWriteAheadLogging - should not be allowed
        mDatabase.execSQL("attach database ':memory:' as memoryDb")
        assertFalse(mDatabase.isWriteAheadLoggingEnabled())
        assertFalse(mDatabase.enableWriteAheadLogging())
        assertFalse(mDatabase.isWriteAheadLoggingEnabled())
        // enableWriteAheadLogging on memory database is not allowed
        val db = SQLiteDatabase.create(null)
        assertFalse(mDatabase.isWriteAheadLoggingEnabled())
        assertFalse(db.enableWriteAheadLogging())
        assertFalse(mDatabase.isWriteAheadLoggingEnabled())
        db.close()
    }
    fun testEnableThenDisableWriteAheadLogging() {
        // Enable WAL.
        assertFalse(mDatabase.isWriteAheadLoggingEnabled())
        assertTrue(mDatabase.enableWriteAheadLogging())
        assertTrue(mDatabase.isWriteAheadLoggingEnabled())
        assertTrue(DatabaseUtils.stringForQuery(mDatabase, "PRAGMA journal_mode", null)
                .equalsIgnoreCase("WAL"))
        // Enabling when already enabled should have no observable effect.
        assertTrue(mDatabase.enableWriteAheadLogging())
        assertTrue(mDatabase.isWriteAheadLoggingEnabled())
        assertTrue(DatabaseUtils.stringForQuery(mDatabase, "PRAGMA journal_mode", null)
                .equalsIgnoreCase("WAL"))
        // Disabling when there are no connections should work.
        mDatabase.disableWriteAheadLogging()
        assertFalse(mDatabase.isWriteAheadLoggingEnabled())
    }
    fun testEnableThenDisableWriteAheadLoggingUsingOpenFlag() {
        closeAndDeleteDatabase()
        mDatabase = SQLiteDatabase.openDatabase(mDatabaseFile.getPath(), null,
                SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING, null)
        assertTrue(mDatabase.isWriteAheadLoggingEnabled())
        assertTrue(DatabaseUtils.stringForQuery(mDatabase, "PRAGMA journal_mode", null)
                .equalsIgnoreCase("WAL"))
        // Enabling when already enabled should have no observable effect.
        assertTrue(mDatabase.enableWriteAheadLogging())
        assertTrue(mDatabase.isWriteAheadLoggingEnabled())
        assertTrue(DatabaseUtils.stringForQuery(mDatabase, "PRAGMA journal_mode", null)
                .equalsIgnoreCase("WAL"))
        // Disabling when there are no connections should work.
        mDatabase.disableWriteAheadLogging()
        assertFalse(mDatabase.isWriteAheadLoggingEnabled())
    }
    fun testEnableWriteAheadLoggingFromContextUsingModeFlag() {
        // Without the MODE_ENABLE_WRITE_AHEAD_LOGGING flag, database opens without WAL.
        closeAndDeleteDatabase()
        mDatabase = getContext().openOrCreateDatabase(DATABASE_FILE_NAME,
                Context.MODE_PRIVATE, null)
        assertFalse(mDatabase.isWriteAheadLoggingEnabled())
        // With the MODE_ENABLE_WRITE_AHEAD_LOGGING flag, database opens with WAL.
        closeAndDeleteDatabase()
        mDatabase = getContext().openOrCreateDatabase(DATABASE_FILE_NAME,
                Context.MODE_PRIVATE or Context.MODE_ENABLE_WRITE_AHEAD_LOGGING, null)
        assertTrue(mDatabase.isWriteAheadLoggingEnabled())
        mDatabase.close()
    }
    fun testEnableWriteAheadLoggingShouldThrowIfTransactionInProgress() {
        assertFalse(mDatabase.isWriteAheadLoggingEnabled())
        val oldJournalMode = DatabaseUtils.stringForQuery(
                mDatabase, "PRAGMA journal_mode", null)
        // Begin transaction.
        mDatabase.beginTransaction()
        try
        {
            // Attempt to enable WAL should fail.
            mDatabase.enableWriteAheadLogging()
            fail("Expected IllegalStateException")
        }
        catch (ex:IllegalStateException) {
            // expected
        }
        assertFalse(mDatabase.isWriteAheadLoggingEnabled())
        assertTrue(DatabaseUtils.stringForQuery(mDatabase, "PRAGMA journal_mode", null)
                .equalsIgnoreCase(oldJournalMode))
    }
    fun testDisableWriteAheadLoggingShouldThrowIfTransactionInProgress() {
        // Enable WAL.
        assertFalse(mDatabase.isWriteAheadLoggingEnabled())
        assertTrue(mDatabase.enableWriteAheadLogging())
        assertTrue(mDatabase.isWriteAheadLoggingEnabled())
        // Begin transaction.
        mDatabase.beginTransaction()
        try
        {
            // Attempt to disable WAL should fail.
            mDatabase.disableWriteAheadLogging()
            fail("Expected IllegalStateException")
        }
        catch (ex:IllegalStateException) {
            // expected
        }
        assertTrue(mDatabase.isWriteAheadLoggingEnabled())
        assertTrue(DatabaseUtils.stringForQuery(mDatabase, "PRAGMA journal_mode", null)
                .equalsIgnoreCase("WAL"))
    }
    fun testEnableAndDisableForeignKeys() {
        // Initially off.
        assertEquals(0, DatabaseUtils.longForQuery(mDatabase, "PRAGMA foreign_keys", null))
        // Enable foreign keys.
        mDatabase.setForeignKeyConstraintsEnabled(true)
        assertEquals(1, DatabaseUtils.longForQuery(mDatabase, "PRAGMA foreign_keys", null))
        // Disable foreign keys.
        mDatabase.setForeignKeyConstraintsEnabled(false)
        assertEquals(0, DatabaseUtils.longForQuery(mDatabase, "PRAGMA foreign_keys", null))
        // Cannot configure foreign keys if there are transactions in progress.
        mDatabase.beginTransaction()
        try
        {
            mDatabase.setForeignKeyConstraintsEnabled(true)
            fail("Expected IllegalStateException")
        }
        catch (ex:IllegalStateException) {
            // expected
        }
        assertEquals(0, DatabaseUtils.longForQuery(mDatabase, "PRAGMA foreign_keys", null))
        mDatabase.endTransaction()
        // Enable foreign keys should work again after transaction complete.
        mDatabase.setForeignKeyConstraintsEnabled(true)
        assertEquals(1, DatabaseUtils.longForQuery(mDatabase, "PRAGMA foreign_keys", null))
    }
    fun testOpenDatabaseLookasideConfig() {
        // First check that lookaside is enabled (except low-RAM devices)
        val expectDisabled = mContext.getSystemService(ActivityManager::class.java).isLowRamDevice()
        verifyLookasideStats(expectDisabled)
        // Reopen test db with lookaside disabled
        mDatabase.close()
        val params = SQLiteDatabase.OpenParams.Builder()
                .setLookasideConfig(0, 0).build()
        mDatabase = SQLiteDatabase.openDatabase(mDatabaseFile, params)
        verifyLookasideStats(true)
        // Reopen test db with custom lookaside config
        mDatabase.close()
        params = SQLiteDatabase.OpenParams.Builder().setLookasideConfig(10000, 10).build()
        mDatabase = SQLiteDatabase.openDatabase(mDatabaseFile, params)
        // Lookaside is always disabled on low-RAM devices
        verifyLookasideStats(expectDisabled)
    }
    fun testOpenParamsSetLookasideConfigValidation() {
        try
        {
            SQLiteDatabase.OpenParams.Builder().setLookasideConfig(-1, 0).build()
            fail("Negative slot size should be rejected")
        }
        catch (expected:IllegalArgumentException) {}
        try
        {
            SQLiteDatabase.OpenParams.Builder().setLookasideConfig(0, -10).build()
            fail("Negative slot count should be rejected")
        }
        catch (expected:IllegalArgumentException) {}
    }
    private fun verifyLookasideStats(expectDisabled:Boolean) {
        val dbStatFound = false
        val info = SQLiteDebug.getDatabaseInfo()
        for (dbStat in info.dbStats)
        {
            if (dbStat.dbName.endsWith(mDatabaseFile.getName()))
            {
                dbStatFound = true
                Log.i(TAG, "Lookaside for " + dbStat.dbName + " " + dbStat.lookaside)
                if (expectDisabled)
                {
                    assertTrue("lookaside slots count should be zero", dbStat.lookaside === 0)
                }
                else
                {
                    assertTrue("lookaside slots count should be greater than zero",
                            dbStat.lookaside > 0)
                }
            }
        }
        assertTrue("No dbstat found for " + mDatabaseFile.getName(), dbStatFound)
    }
    @Throws(Exception::class)
    fun testCloseIdleConnection() {
        mDatabase.close()
        val params = SQLiteDatabase.OpenParams.Builder()
                .setIdleConnectionTimeout(1000).build()
        mDatabase = SQLiteDatabase.openDatabase(mDatabaseFile, params)
        // Wait a bit and check that connection is still open
        Thread.sleep(600)
        val output = getDbInfoOutput()
        assertTrue("Connection #0 should be open. Output: " + output,
                output.contains("Connection #0:"))
        // Now cause idle timeout and check that connection is closed
        // We wait up to 5 seconds, which is longer than required 1 s to accommodate for delays in
        // message processing when system is busy
        val connectionWasClosed = waitForConnectionToClose(10, 500)
        assertTrue("Connection #0 should be closed", connectionWasClosed)
    }
    @Throws(Exception::class)
    fun testNoCloseIdleConnectionForAttachDb() {
        mDatabase.close()
        val params = SQLiteDatabase.OpenParams.Builder()
                .setIdleConnectionTimeout(50).build()
        mDatabase = SQLiteDatabase.openDatabase(mDatabaseFile, params)
        // Attach db and verify size of the list of attached databases (includes main db)
        assertEquals(1, mDatabase.getAttachedDbs().size())
        mDatabase.execSQL("ATTACH DATABASE ':memory:' as memdb")
        assertEquals(2, mDatabase.getAttachedDbs().size())
        // Wait longer (500ms) to catch cases when timeout processing was delayed
        val connectionWasClosed = waitForConnectionToClose(5, 100)
        assertFalse("Connection #0 should be open", connectionWasClosed)
    }
    @Throws(Exception::class)
    fun testSetIdleConnectionTimeoutValidation() {
        try
        {
            SQLiteDatabase.OpenParams.Builder().setIdleConnectionTimeout(-1).build()
            fail("Negative timeout should be rejected")
        }
        catch (expected:IllegalArgumentException) {}
    }*/
    companion object {
        private val TAG = "SQLiteDatabaseTest"
        private val DATABASE_FILE_NAME = "database_test.db"
        private val TABLE_NAME = "test"
        private val COLUMN_ID_INDEX = 0
        private val COLUMN_NAME_INDEX = 1
        private val COLUMN_AGE_INDEX = 2
        private val COLUMN_ADDR_INDEX = 3
        private val TEST_PROJECTION = arrayOf<String>("_id", // 0
                "name", // 1
                "age", // 2
                "address" // 3
        )
    }
}
