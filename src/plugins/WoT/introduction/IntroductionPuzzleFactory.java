/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.introduction;

import java.io.IOException;

import plugins.WoT.Identity;

/**
 * @author xor
 *
 */
public interface IntroductionPuzzleFactory {
	
	public IntroductionPuzzle generatePuzzle(Identity inserter, int index) throws IOException;

}