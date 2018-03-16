/*
 * java-tron is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-tron is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.tron.common.application;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.typesafe.config.Config;

public class ApplicationFactory {

  /**
   * Build a Guice instance.
   *
   * @return Guice
   */
  public static Injector buildGuice(Config config) {
    return Guice.createInjector(new Module(config));
  }

  /**
   * Build a new application.
   */
  public Application build() {
    return new ApplicationImpl();
  }

  /**
   * Build a new cli application.
   */
  public static Application create() {
    return new ApplicationImpl();
  }
}
