/**
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.driver.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.neo4j.driver.internal.spi.Connection;
import org.neo4j.driver.internal.spi.StreamCollector;
import org.neo4j.driver.internal.summary.SummaryBuilder;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Statement;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.Value;
import org.neo4j.driver.v1.exceptions.ClientException;
import org.neo4j.driver.v1.exceptions.NoSuchRecordException;
import org.neo4j.driver.v1.summary.Notification;
import org.neo4j.driver.v1.summary.Plan;
import org.neo4j.driver.v1.summary.ProfiledPlan;
import org.neo4j.driver.v1.summary.ResultSummary;
import org.neo4j.driver.v1.summary.StatementType;
import org.neo4j.driver.v1.summary.SummaryCounters;
import org.neo4j.driver.v1.util.Function;
import org.neo4j.driver.v1.util.Functions;

import static java.lang.String.format;
import static java.util.Collections.emptyList;

public class InternalStatementResult implements StatementResult
{
    private final Connection connection;
    private final StreamCollector runResponseCollector;
    private final StreamCollector pullAllResponseCollector;
    private final Queue<Record> recordBuffer = new LinkedList<>();

    private List<String> keys = null;
    private ResultSummary summary = null;

    private boolean open = true;
    private long position = -1;
    private boolean done = false;

    public InternalStatementResult( Connection connection, Statement statement )
    {
        this.connection = connection;
        this.runResponseCollector = newRunResponseCollector();
        this.pullAllResponseCollector = newPullAllResponseCollector( statement );
    }

    private StreamCollector newRunResponseCollector()
    {
        return new StreamCollector()
        {
            @Override
            public void keys( String[] names )
            {
                keys = Arrays.asList( names );
            }

            @Override
            public void record( Value[] fields ) {}

            @Override
            public void statementType( StatementType type ) {}

            @Override
            public void statementStatistics( SummaryCounters statistics ) {}

            @Override
            public void plan( Plan plan ) {}

            @Override
            public void profile( ProfiledPlan plan ) {}

            @Override
            public void notifications( List<Notification> notifications ) {}

            @Override
            public void done()
            {
                if ( keys == null )
                {
                    keys = new ArrayList<>();
                }
            }
        };
    }

    private StreamCollector newPullAllResponseCollector( Statement statement )
    {
        final SummaryBuilder summaryBuilder = new SummaryBuilder( statement );
        return new StreamCollector()
        {
            @Override
            public void keys( String[] names ) {}

            @Override
            public void record( Value[] fields )
            {
                recordBuffer.add( new InternalRecord( keys, fields ) );
            }

            @Override
            public void statementType( StatementType type )
            {
                summaryBuilder.statementType( type );
            }

            @Override
            public void statementStatistics( SummaryCounters statistics )
            {
                summaryBuilder.statementStatistics( statistics );
            }

            @Override
            public void plan( Plan plan )
            {
                summaryBuilder.plan( plan );
            }

            @Override
            public void profile( ProfiledPlan plan )
            {
                summaryBuilder.profile( plan );
            }

            @Override
            public void notifications( List<Notification> notifications )
            {
                summaryBuilder.notifications( notifications );
            }

            @Override
            public void done() {
                summary = summaryBuilder.build();
                done = true;
            }
        };
    }

    StreamCollector runResponseCollector()
    {
        return runResponseCollector;
    }

    StreamCollector pullAllResponseCollector()
    {
        return pullAllResponseCollector;
    }

    @Override
    public List<String> keys()
    {
        tryFetching();
        return keys;
    }

    @Override
    public boolean hasNext()
    {
        if (!recordBuffer.isEmpty())
        {
            return true;
        }
        else if (done)
        {
            return false;
        }
        else
        {
            tryFetching();
            return hasNext();
        }
    }

    @Override
    public Record next()
    {
        // Implementation note:
        // We've chosen to use Iterator<Record> over a cursor-based version,
        // after tests show escape analysis will eliminate short-lived allocations
        // in a way that makes the two equivalent in performance.
        // To get the intended benefit, we need to allocate Record in this method,
        // and have it copy out its fields from some lower level data structure.
        assertOpen();
        Record nextRecord = recordBuffer.poll();
        if ( nextRecord != null )
        {
            position += 1;
            return nextRecord;
        }
        else if ( done )
        {
            return null;
        }
        else
        {
            tryFetching();
            return next();
        }
    }

    @Override
    public Record single()
    {
        if( position > 0 )
        {
            throw new NoSuchRecordException(
                    "Cannot retrieve the first record, because other operations have already used the first record. " +
                    "Please ensure you are not calling `first` multiple times, or are mixing it with calls " +
                    "to `next`, `single`, `list` or any other method that changes the position of the cursor." );
        }

        if( !hasNext() )
        {
            throw new NoSuchRecordException( "Cannot retrieve the first record, because this result is empty." );
        }

        Record first = next();
        if( hasNext() )
        {
            throw new NoSuchRecordException( "Expected a result with a single record, but this result contains at least one more. " +
                    "Ensure your query returns only one record, or use `first` instead of `single` if " +
                    "you do not care about the number of records in the result." );
        }
        return first;
    }

    @Override
    public Record peek()
    {
        assertOpen();
        Record nextRecord = recordBuffer.peek();
        if ( nextRecord != null )
        {
            return nextRecord;
        }
        else if ( done )
        {
            return null;
        }
        else
        {
            tryFetching();
            return peek();
        }
    }

    @Override
    public List<Record> list()
    {
        return list( Functions.<Record>identity() );
    }

    @Override
    public <T> List<T> list( Function<Record, T> mapFunction )
    {
        if ( isEmpty() )
        {
            assertOpen();
            return emptyList();
        }
        else if ( position == -1 && hasNext() )
        {
            List<T> result = new ArrayList<>();
            do
            {
                result.add( mapFunction.apply( next() ) );
            }
            while ( hasNext() );

            consume();
            return result;
        }
        else
        {
            throw new ClientException(
                    format( "Can't retain records when cursor is not pointing at the first record (currently at position %d)", position )
            );
        }
    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public ResultSummary consume()
    {
        if(!open)
        {
            return summary;
        }

        while ( !done )
        {
            connection.receiveOne();
        }
        recordBuffer.clear();
        open = false;
        return summary;
    }

    @Override
    public void remove()
    {
        throw new ClientException( "Removing records from a result is not supported." );
    }

    private void assertOpen()
    {
        if ( !open )
        {
            throw new ClientException( "Result has been closed" );
        }
    }

    private boolean isEmpty()
    {
        tryFetching();
        return position == -1 && recordBuffer.isEmpty() && done;
    }

    private void tryFetching()
    {
        while ( recordBuffer.isEmpty() && !done )
        {
            connection.receiveOne();
        }
    }

}