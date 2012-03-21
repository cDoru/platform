/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog
package ingest

import service._
import ingest.service._
import common.security._
import daze._

import akka.dispatch.Future
import akka.dispatch.MessageDispatcher

import blueeyes.bkka.AkkaDefaults
import blueeyes.bkka.Stoppable
import blueeyes.BlueEyesServiceBuilder
import blueeyes.core.data.{BijectionsChunkJson, BijectionsChunkFutureJson, BijectionsChunkString, ByteChunk}
import blueeyes.health.metrics.{eternity}

import org.streum.configrity.Configuration

case class IngestState(tokenManager: TokenManager, accessControl: AccessControl, eventStore: EventStore, usageLogging: UsageLogging)

trait IngestService extends BlueEyesServiceBuilder with IngestServiceCombinators with AkkaDefaults { 
  import BijectionsChunkJson._
  import BijectionsChunkString._
  import BijectionsChunkFutureJson._

  val insertTimeout = akka.util.Timeout(10000)
  implicit val timeout = akka.util.Timeout(120000) //for now
  def tokenManagerFactory(config: Configuration): TokenManager
  def eventStoreFactory(config: Configuration): EventStore
  def usageLoggingFactory(config: Configuration): UsageLogging 

  val analyticsService = this.service("ingest", "1.0") {
    requestLogging(timeout) {
      healthMonitor(timeout, List(eternity)) { monitor => context =>
        startup {
          import context._

          val eventStore = eventStoreFactory(config.detach("eventStore"))
          val theTokenManager = tokenManagerFactory(config.detach("security"))
          val accessControl = new TokenBasedAccessControl { 
            val executionContext = defaultFutureDispatch
            val tokenManager = theTokenManager  
          }

          eventStore.start map { _ =>
            IngestState(
              theTokenManager,
              accessControl,
              eventStore,
              usageLoggingFactory(config.detach("usageLogging"))
            )
          }
        } ->
        request { (state: IngestState) =>
          jsonp[ByteChunk] {
            token(state.tokenManager) {
              dataPath("track") {
                post(new TrackingServiceHandler(state.accessControl, state.eventStore, state.usageLogging, insertTimeout)(defaultFutureDispatch))
              }
            }
          }
        } ->
        shutdown { state => Future[Option[Stoppable]]( None ) }
      }
    }
  }
}
