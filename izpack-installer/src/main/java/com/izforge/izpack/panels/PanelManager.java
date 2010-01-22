package com.izforge.izpack.panels;

import com.izforge.izpack.api.adaptator.IXMLElement;
import com.izforge.izpack.api.adaptator.impl.XMLElementImpl;
import com.izforge.izpack.api.data.AutomatedInstallData;
import com.izforge.izpack.api.data.Panel;
import com.izforge.izpack.data.PanelAction;
import com.izforge.izpack.installer.DataValidatorFactory;
import com.izforge.izpack.installer.PanelActionFactory;
import com.izforge.izpack.installer.base.IzPanel;
import com.izforge.izpack.installer.container.IInstallerContainer;
import com.izforge.izpack.installer.data.GUIInstallData;
import com.izforge.izpack.installer.unpacker.IUnpacker;
import com.izforge.izpack.util.AbstractUIHandler;
import com.izforge.izpack.util.AbstractUIProgressHandler;
import com.izforge.izpack.util.OsConstraint;

import java.io.File;
import java.io.FileFilter;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Load panels in the container
 */
public class PanelManager {

    public static String CLASSNAME_PREFIX = "com.izforge.izpack.installer.panels";
    public static String BASE_CLASSNAME_PATH = "com/izforge/izpack/installer/panels";

    private GUIInstallData installdata;
    private IInstallerContainer installerContainer;
    private int lastVis;

    /**
     * Mapping from "raw" panel number to visible panel number.
     */
    protected ArrayList<Integer> visiblePanelMapping;

    public PanelManager(GUIInstallData installDataGUI, IInstallerContainer installerContainer) throws ClassNotFoundException {
        this.installdata = installDataGUI;
        this.installerContainer = installerContainer;
        visiblePanelMapping = new ArrayList<Integer>();
    }

    public Class<? extends IzPanel> resolveClassName(final String className) throws ClassNotFoundException {
        URL resource = ClassLoader.getSystemClassLoader().getResource(BASE_CLASSNAME_PATH);
        File panelDirectory = new File(resource.getFile());
        FileFilter fileFilter = new FileFilter() {
            public boolean accept(File pathname) {
                return pathname.isDirectory() ||
                        pathname.getName().replaceAll(".class", "").equalsIgnoreCase(className);
            }
        };
        File classFile = findRecursivelyForFile(fileFilter, panelDirectory);
        if (classFile != null) {
            return resolveClassFromPath(panelDirectory, classFile);
        }
        return resolveClassFromName(className);
    }

    private Class<? extends IzPanel> resolveClassFromName(String className) throws ClassNotFoundException {
        Class<?> aClass;
        if (!className.contains(".")) {
            aClass = Class.forName(CLASSNAME_PREFIX + "." + className);
        } else {
            aClass = Class.forName(className);
        }
        return (Class<? extends IzPanel>) aClass;
    }

    /**
     * From a class file found in the classpath, convert it to a className
     *
     * @param panelDirectory Base directory
     * @param classFile      Path to the class file
     * @return The resolved class
     * @throws ClassNotFoundException
     */
    private Class resolveClassFromPath(File panelDirectory, File classFile) throws ClassNotFoundException {
        String result = classFile.getAbsolutePath().replaceAll(panelDirectory.getAbsolutePath(), "");
        result = CLASSNAME_PREFIX + result.replaceAll("/", ".").replaceAll(".class", "");
        return Class.forName(result);
    }

    /**
     * Recursively search a file matching the fileFilter
     *
     * @param fileFilter  Filter accepting directory and file matching a classname pattern
     * @param currentFile Current directory
     * @return the first found file or null
     */
    private File findRecursivelyForFile(FileFilter fileFilter, File currentFile) {
        if (currentFile.isDirectory()) {
            for (File files : currentFile.listFiles(fileFilter)) {
                System.out.println(files.getAbsolutePath());
                File file = findRecursivelyForFile(fileFilter, files);
                if (file != null) {
                    return file;
                }
            }
        } else {
            return currentFile;
        }
        return null;
    }

    /**
     * Parse XML to search all used panels and add them in the pico installerContainer.
     *
     * @throws ClassNotFoundException
     */
    public PanelManager loadPanelsInContainer() throws ClassNotFoundException {
        // Initialisation
        // We load each of them
        java.util.List<Panel> panelsOrder = installdata.getPanelsOrder();
        for (Panel panel : panelsOrder) {
            System.out.println(panel.getClassName());
            if (OsConstraint.oneMatchesCurrentSystem(panel.osConstraints)) {
                Class<? extends IzPanel> aClass = resolveClassName(panel.getClassName());
                installerContainer.addComponent(aClass);
            }
        }
        return this;
    }

    /**
     * Construct all panels present in the installerContainer.<br />
     * Executing prebuild, prevalidate, postvalidate and postconstruct actions.
     *
     * @throws ClassNotFoundException
     */
    public void instanciatePanels() throws ClassNotFoundException {
        java.util.List<Panel> panelsOrder = installdata.getPanelsOrder();
        int curVisPanelNumber = 0;
        lastVis = 0;
        int count = 0;
        for (Panel panel : panelsOrder) {
            if (OsConstraint.oneMatchesCurrentSystem(panel.osConstraints)) {
                Class<? extends IzPanel> aClass = resolveClassName(panel.getClassName());
                executePreBuildActions(panel);
                IzPanel izPanel = installerContainer.getComponent(aClass);
                izPanel.setMetadata(panel);
                String dataValidator = panel.getValidator();
                if (dataValidator != null) {
                    izPanel.setValidationService(DataValidatorFactory.createDataValidator(dataValidator));
                }
                izPanel.setHelps(panel.getHelpsMap());

                preValidateAction(panel, izPanel);
                postValidateAction(panel, izPanel);

                installdata.getPanels().add(izPanel);
                if (izPanel.isHidden()) {
                    visiblePanelMapping.add(count, -1);
                } else {
                    visiblePanelMapping.add(count, curVisPanelNumber);
                    curVisPanelNumber++;
                    lastVis = count;
                }
                count++;
                // We add the XML installDataGUI izPanel root
                IXMLElement panelRoot = new XMLElementImpl(panel.getClassName(), installdata.getXmlData());
                // if set, we add the id as an attribute to the panelRoot
                String panelId = panel.getPanelid();
                if (panelId != null) {
                    panelRoot.setAttribute("id", panelId);
                }
                installdata.getXmlData().addChild(panelRoot);
            }
            visiblePanelMapping.add(count, lastVis);
        }
    }


    public void executePreBuildActions(Panel panel) {
        List<String> preConstgructionActions = panel.getPreConstructionActions();
        if (preConstgructionActions != null) {
            for (String preConstgructionAction : preConstgructionActions) {
                PanelAction action = PanelActionFactory.createPanelAction(preConstgructionAction);
                action.initialize(panel.getPanelActionConfiguration(preConstgructionAction));
                action.executeAction(AutomatedInstallData.getInstance(), null);
            }
        }
    }

    private void preValidateAction(Panel panel, IzPanel izPanel) {
        List<String> preActivateActions = panel.getPreActivationActions();
        if (preActivateActions != null) {
            for (String panelActionClass : preActivateActions) {
                PanelAction action = PanelActionFactory.createPanelAction(panelActionClass);
                action.initialize(panel.getPanelActionConfiguration(panelActionClass));
                izPanel.addPreActivationAction(action);
            }
        }
    }

    private void postValidateAction(Panel panel, IzPanel izPanel) {
        List<String> postValidateActions = panel.getPostValidationActions();
        if (postValidateActions != null) {
            for (String panelActionClass : postValidateActions) {
                PanelAction action = PanelActionFactory.createPanelAction(panelActionClass);
                action.initialize(panel.getPanelActionConfiguration(panelActionClass));
                izPanel.addPostValidationAction(action);
            }
        }
    }

    public boolean isVisible(int panelNumber) {
        return !(visiblePanelMapping.get(panelNumber) == -1);
    }

    public boolean isLast(int panelNumber) {
        return (visiblePanelMapping.get(installdata.getPanels().size()) == panelNumber);
    }

    public int getPanelVisibilityNumber(int panel) {
        return visiblePanelMapping.get(panel);
    }

    public void setAbstractUIHandlerInContainer(AbstractUIHandler abstractUIHandlerInContainer) {
//        installerContainer.removeComponent(AbstractUIHandler.class);
//        installerContainer.addComponent(AbstractUIHandler.class, abstractUIHandlerInContainer);
    }

    public int getCountVisiblePanel() {
        return lastVis;
    }

    public IUnpacker getUnpacker(AbstractUIProgressHandler listener) {
        setAbstractUIHandlerInContainer(listener);
        return installerContainer.getComponent(IUnpacker.class);
    }
}
