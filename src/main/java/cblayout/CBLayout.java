/*************************************************************************************
 * The Exemplary Add-on is (c) 2008-2011 Plant Bioinformatics Group, IPK Gatersleben,
 * http://bioinformatics.ipk-gatersleben.de
 * The source code for this project, which is developed by our group, is
 * available under the GPL license v2.0 available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html. By using this
 * Add-on and VANTED you need to accept the terms and conditions of this
 * license, the below stated disclaimer of warranties and the licenses of
 * the used libraries. For further details see license.txt in the root
 * folder of this project.
 ************************************************************************************/
package cblayout;

import gui.AddonTab;
import layout.sbgn.SbgnStepAlgorithm;
import layout.steps.StepLayoutRegistry;

import de.ipk_gatersleben.ag_nw.graffiti.plugins.addons.AddonAdapter;
import org.graffiti.plugin.algorithm.Algorithm;
import org.graffiti.plugin.gui.GraffitiComponent;
import org.graffiti.plugin.inspector.InspectorTab;

/**
 * The VANTED add-on "Constraint SBGN Layout": the inspector tab "Constraint-Based Layout", the Layout menu entries
 * "Constraint SBGN Layout" and "Apply constraints", the SBGN step algorithms on the Algorithms page and the toolbar
 * buttons that arrange the graph windows.
 */
public class CBLayout extends AddonAdapter {

    @Override
    protected void initializeAddon() {
        // another add-on registers its step algorithms the same way (see StepAlgorithm)
        StepLayoutRegistry.register(new SbgnStepAlgorithm());
        this.tabs = new InspectorTab[]{new AddonTab()};

        this.algorithms = new Algorithm[]{
                new ConstraintSBGNLayoutAlgorithm(),
                new ApplyConstraintsAlgorithm()
        };

        this.guiComponents = new GraffitiComponent[]{
                new ToolbarButton("defaultToolbar", WindowOrder.HORIZONTAL),
                new ToolbarButton("defaultToolbar", WindowOrder.VERTICAL),
                new ToolbarButton("defaultToolbar", WindowOrder.QUADRATIC)
        };
    }
}
