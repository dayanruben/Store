package org.mobilenativefoundation.store.store5

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.SourceOfTruth.ReadException
import org.mobilenativefoundation.store.store5.SourceOfTruth.WriteException
import org.mobilenativefoundation.store.store5.util.FakeFetcher
import org.mobilenativefoundation.store.store5.util.InMemoryPersister
import org.mobilenativefoundation.store.store5.util.asSourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@FlowPreview
class SourceOfTruthErrorsTests {
    private val testScope = TestScope()

    @Test
    fun givenSourceOfTruthWhenWriteFailsThenExceptionShouldBeSendToTheCollector() =
        testScope.runTest {
            val persister = InMemoryPersister<Int, String>()
            val fetcher =
                FakeFetcher(
                    3 to "a",
                    3 to "b",
                )
            val pipeline =
                StoreBuilder
                    .from<Int, String, String>(
                        fetcher = fetcher,
                        sourceOfTruth = persister.asSourceOfTruth(),
                    )
                    .scope(testScope)
                    .build()
            persister.preWriteCallback = { _, _ ->
                throw TestException("i fail")
            }

            pipeline.stream(StoreReadRequest.fresh(3)).test {
                assertEquals(
                    StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()),
                    awaitItem(),
                )

                assertEquals(
                    StoreReadResponse.Error.Exception(
                        error =
                            WriteException(
                                key = 3,
                                value = "a",
                                cause = TestException("i fail"),
                            ),
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )
            }
        }

    @Test
    fun givenSourceOfTruthWhenReadFailsThenExceptionShouldBeSendToTheCollector() =
        testScope.runTest {
            val persister = InMemoryPersister<Int, String>()
            val fetcher =
                FakeFetcher(
                    3 to "a",
                    3 to "b",
                )
            val pipeline =
                StoreBuilder
                    .from<Int, String, String>(
                        fetcher = fetcher,
                        sourceOfTruth = persister.asSourceOfTruth(),
                    )
                    .scope(testScope)
                    .build()

            persister.postReadCallback = { _, value ->
                throw TestException(value ?: "null")
            }

            pipeline.stream(StoreReadRequest.cached(3, refresh = false)).test {
                assertEquals(
                    StoreReadResponse.Error.Exception(
                        error =
                            ReadException(
                                key = 3,
                                cause = TestException("null"),
                            ),
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )

                // after disk fails, we should still invoke fetcher
                assertEquals(
                    StoreReadResponse.Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                // and after fetcher writes the value, it will trigger another read which will also
                // fail
                assertEquals(
                    StoreReadResponse.Error.Exception(
                        error =
                            ReadException(
                                key = 3,
                                cause = TestException("a"),
                            ),
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )
            }
        }

    @Test
    fun givenSourceOfTruthWhenFirstWriteFailsThenItShouldKeepReadingFromFetcher() =
        testScope.runTest {
            val persister = InMemoryPersister<Int, String>()
            val fetcher =
                Fetcher.ofFlow { _: Int ->
                    flowOf("a", "b", "c", "d")
                }
            val pipeline =
                StoreBuilder
                    .from<Int, String, String>(
                        fetcher = fetcher,
                        sourceOfTruth = persister.asSourceOfTruth(),
                    )
                    .disableCache()
                    .scope(testScope)
                    .build()
            persister.preWriteCallback = { _, value ->
                if (value in listOf("a", "c")) {
                    throw TestException(value)
                }
                value
            }
            pipeline.stream(StoreReadRequest.cached(3, refresh = true)).test {
                assertEquals(
                    StoreReadResponse.Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
                assertEquals(
                    StoreReadResponse.Error.Exception(
                        error =
                            WriteException(
                                key = 3,
                                value = "a",
                                cause = TestException("a"),
                            ),
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )

                assertEquals(
                    StoreReadResponse.Data(
                        value = "b",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    StoreReadResponse.Error.Exception(
                        error =
                            WriteException(
                                key = 3,
                                value = "c",
                                cause = TestException("c"),
                            ),
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )

                // disk flow will restart after a failed write (because we stopped it before the
                // write attempt starts, so we will get the disk value again).
                assertEquals(
                    StoreReadResponse.Data(
                        value = "b",
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )

                assertEquals(
                    StoreReadResponse.Data(
                        value = "d",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }
        }

//    @Test
//    fun givenSourceOfTruthWithFailingWriteWhenAPassiveReaderArrivesThenItShouldReceiveTheNewWriteError() = testScope.runTest {
//        val persister = InMemoryPersister<Int, String>()
//        val fetcher = Fetcher.ofFlow { _: Int ->
//            flowOf("a", "b", "c", "d")
//        }
//        val pipeline = StoreBuilder
//            .from<Int, String>(
//                fetcher = fetcher,
//                sourceOfTruth = persister.asSourceOfTruth()
//            )
//            .disableCache()
//            .scope(testScope)
//            .build()
//        persister.preWriteCallback = { _, value ->
//            if (value in listOf("a", "c")) {
//                delay(50)
//                throw TestException(value)
//            } else {
//                delay(10)
//            }
//            value
//        }
//        // keep collection hot
//        val collector = launch {
//            pipeline.stream(
//                StoreReadRequest.cached(3, refresh = true)
//            ).toList()
//        }
//
//        // miss writes for a and b and let the write operation for c start such that
//        // we'll catch that write error
//        delay(70)
//        assertEmitsExactly(
//            pipeline.stream(StoreReadRequest.cached(3, refresh = true)),
//            listOf(
//                // we wanted the disk value but write failed so we don't get it
//                StoreReadResponse.Error.Exception(
//                    error = WriteException(
//                        key = 3,
//                        value = "c",
//                        cause = TestException("c")
//                    ),
//                    origin = StoreReadResponseOrigin.SourceOfTruth
//                ),
//                // after the write error, we should get the value on disk
//                StoreReadResponse.Data(
//                    value = "b",
//                    origin = StoreReadResponseOrigin.SourceOfTruth
//                ),
//                // now we'll unlock the fetcher after disk is read
//                StoreReadResponse.Loading(
//                    origin = StoreReadResponseOrigin.Fetcher(
//                ),
//                StoreReadResponse.Data(
//                    value = "d",
//                    origin = StoreReadResponseOrigin.Fetcher(
//                )
//            )
//        )
//        collector.cancelAndJoin()
//    }

    @Test
    fun givenSourceOfTruthWithFailingWriteWhenAPassiveReaderArrivesThenItShouldNotGetErrorsHappenedBefore() =
        testScope.runTest {
            val persister = InMemoryPersister<Int, String>()
            val fetcher =
                Fetcher.ofFlow<Int, String> {
                    flow {
                        emit("a")
                        emit("b")
                        emit("c")
                        // now delay, wait for the new subscriber
                        delay(100)
                        emit("d")
                    }
                }
            val pipeline =
                StoreBuilder
                    .from<Int, String, String>(
                        fetcher = fetcher,
                        sourceOfTruth = persister.asSourceOfTruth(),
                    )
                    .disableCache()
                    .scope(testScope)
                    .build()
            persister.preWriteCallback = { _, value ->
                if (value in listOf("a", "c")) {
                    throw TestException(value)
                }
                value
            }
            val collector =
                launch {
                    pipeline.stream(
                        StoreReadRequest.cached(3, refresh = true),
                    ).toList() // keep collection hot
                }

            // miss both failures but arrive before d is fetched
            delay(70)

            pipeline.stream(StoreReadRequest.skipMemory(3, refresh = true)).test {
                assertEquals(
                    StoreReadResponse.Data(
                        value = "b",
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )

                // don't receive the write exception because technically it started before we
                // started reading
                assertEquals(
                    StoreReadResponse.Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    StoreReadResponse.Data(
                        value = "d",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }

            collector.cancelAndJoin()
        }

//    @Test
//    fun givenSourceOfTruthWithFailingWriteWhenAFreshValueReaderArrivesThenItShouldNotGetDiskErrorsFromAPendingWrite() = testScope.runTest {
//        val persister = InMemoryPersister<Int, String>()
//        val fetcher = Fetcher.ofFlow<Int, String> {
//            flowOf("a", "b", "c", "d")
//        }
//        val pipeline = StoreBuilder
//            .from<Int, String>(
//                fetcher = fetcher,
//                sourceOfTruth = persister.asSourceOfTruth()
//            )
//            .disableCache()
//            .scope(testScope)
//            .build()
//        persister.preWriteCallback = { _, value ->
//            if (value == "c") {
//                // slow down read so that the new reader arrives
//                delay(50)
//            }
//            if (value in listOf("a", "c")) {
//                throw TestException(value)
//            }
//            value
//        }
//        val collector = launch {
//            pipeline.stream(
//                StoreReadRequest.cached(3, refresh = true)
//            ).toList() // keep collection hot
//        }
//        // miss both failures but arrive before d is fetched
//        delay(20)
//        assertEmitsExactly(
//            pipeline.stream(StoreReadRequest.fresh(3)),
//            listOf(
//                StoreReadResponse.Loading(
//                    origin = StoreReadResponseOrigin.Fetcher(
//                ),
//                StoreReadResponse.Data(
//                    value = "d",
//                    origin = StoreReadResponseOrigin.Fetcher(
//                )
//            )
//        )
//        collector.cancelAndJoin()
//    }

    @Test
    fun givenSourceOfTruthWithReadFailureWhenCachedValueReaderArrivesThenFetcherShouldBeCalledToGetANewValue() {
        testScope.runTest {
            val persister = InMemoryPersister<Int, String>()
            val fetcher = Fetcher.of { _: Int -> "a" }
            val pipeline =
                StoreBuilder
                    .from<Int, String, String>(
                        fetcher = fetcher,
                        sourceOfTruth = persister.asSourceOfTruth(),
                    )
                    .disableCache()
                    .scope(testScope)
                    .build()
            persister.postReadCallback = { _, value ->
                if (value == null) {
                    throw TestException("first read")
                }
                value
            }
            pipeline.stream(StoreReadRequest.cached(3, refresh = true)).test {
                assertEquals(
                    StoreReadResponse.Error.Exception(
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                        error =
                            ReadException(
                                key = 3,
                                cause = TestException("first read"),
                            ),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    StoreReadResponse.Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    StoreReadResponse.Data(
                        value = "a",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }
        }
    }

    private class TestException(val msg: String) : Exception(msg) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TestException) return false
            return msg == other.msg
        }

        override fun hashCode(): Int {
            return msg.hashCode()
        }
    }
}
