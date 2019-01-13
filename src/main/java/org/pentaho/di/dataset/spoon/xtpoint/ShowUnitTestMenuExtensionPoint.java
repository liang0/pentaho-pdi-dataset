/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2002-2017 by Pentaho : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.di.dataset.spoon.xtpoint;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs2.FileObject;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.extension.ExtensionPoint;
import org.pentaho.di.core.extension.ExtensionPointInterface;
import org.pentaho.di.core.gui.AreaOwner;
import org.pentaho.di.core.logging.LogChannelInterface;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.dataset.TransUnitTest;
import org.pentaho.di.dataset.TransUnitTestSetLocation;
import org.pentaho.di.dataset.spoon.DataSetHelper;
import org.pentaho.di.dataset.spoon.dialog.TransUnitTestSetLocationDialog;
import org.pentaho.di.dataset.util.DataSetConst;
import org.pentaho.di.dataset.util.FactoriesHierarchy;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.ui.core.dialog.ErrorDialog;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.di.ui.spoon.trans.TransGraphExtension;
import org.pentaho.metastore.persist.MetaStoreFactory;
import org.pentaho.metastore.stores.delegate.DelegatingMetaStore;
import org.pentaho.metastore.util.PentahoDefaults;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ExtensionPoint(
  id = "ShowUnitTestMenuExtensionPoint",
  description = "Quick unit test menu",
  extensionPointId = "TransGraphMouseDown" )
public class ShowUnitTestMenuExtensionPoint implements ExtensionPointInterface {
  private static Class<?> PKG = ShowUnitTestMenuExtensionPoint.class; // for i18n purposes, needed by Translator2!!

  @Override
  public void callExtensionPoint( LogChannelInterface log, Object object ) throws KettleException {

    if ( !( object instanceof TransGraphExtension ) ) {
      return;
    }

    TransGraphExtension tge = (TransGraphExtension) object;
    final TransMeta transMeta = tge.getTransGraph().getTransMeta();
    final TransUnitTest unitTest = DataSetHelper.getCurrentUnitTest( transMeta );
    Spoon spoon = Spoon.getInstance();
    MouseEvent e = tge.getEvent();

    if ( e.button == 1 || e.button == 2 ) {
      AreaOwner areaOwner = tge.getTransGraph().getVisibleAreaOwner( tge.getPoint().x, tge.getPoint().y );
      if ( areaOwner != null && areaOwner.getAreaType() != null ) {
        // Check if this is the flask...
        //
        if ( DataSetConst.AREA_DRAWN_UNIT_TEST_ICON.equals( areaOwner.getParent() ) ) {
          final String unitTestName = unitTest != null ? unitTest.getName() : null;

          Canvas canvas = findCanvas( tge.getTransGraph() );
          if ( canvas != null ) {

            Listener lsNew = event -> newUnitTest( transMeta );
            Listener lsEdit = event -> editUnitTest( unitTestName, transMeta );
            Listener lsDelete = event -> deleteUnitTest( unitTestName );
            Listener lsDisable = event -> disableUnitTest( unitTestName );
            Listener lsDataSetNew = event -> DataSetHelper.getInstance().addDataSet();
            Listener lsDataSetEdit = event -> DataSetHelper.getInstance().editDataSet();
            Listener lsDataSetDelete = event -> DataSetHelper.getInstance().deleteDataSet();
            Listener lsGroupNew = event -> DataSetHelper.getInstance().addDataSetGroup();
            Listener lsGroupEdit = event -> DataSetHelper.getInstance().editDataSetGroup();
            Listener lsOpenTrans = event -> DataSetHelper.getInstance().openUnitTestTransformation();
            Listener lsDelTest = event -> DataSetHelper.getInstance().deleteUnitTest();

            Menu menu = new Menu( spoon.getShell(), SWT.POP_UP );
            MenuItem newItem = new MenuItem( menu, SWT.PUSH );
            newItem.setText( BaseMessages.getString( PKG, "ShowUnitTestMenuExtensionPoint.UnitMenu.New.Label" ) );
            newItem.addListener( SWT.Selection, lsNew );

            MenuItem editItem = new MenuItem( menu, SWT.PUSH );
            editItem.setText( BaseMessages.getString( PKG, "ShowUnitTestMenuExtensionPoint.UnitTest.Edit.Label" ) );
            editItem.addListener( SWT.Selection, lsEdit );
            editItem.setEnabled( StringUtils.isNotEmpty( unitTestName ) );

            MenuItem deleteItem = new MenuItem( menu, SWT.PUSH );
            deleteItem.setText( BaseMessages.getString( PKG, "ShowUnitTestMenuExtensionPoint.UnitTest.Delete.Label" ) );
            deleteItem.addListener( SWT.Selection, lsDelete );
            deleteItem.setEnabled( StringUtils.isNotEmpty( unitTestName ) );

            MenuItem disableItem = new MenuItem( menu, SWT.PUSH );
            disableItem.setText( BaseMessages.getString( PKG, "ShowUnitTestMenuExtensionPoint.UnitTest.Disable.Label" ) );
            disableItem.addListener( SWT.Selection, lsDisable );
            disableItem.setEnabled( StringUtils.isNotEmpty( unitTestName ) );


            // Switch
            MenuItem switchItem = new MenuItem( menu, SWT.CASCADE );
            switchItem.setText( BaseMessages.getString( PKG, "ShowUnitTestMenuExtensionPoint.UnitTest.Switch.Label" ) );

            Menu switchMenu = new Menu( menu );
            switchItem.setMenu( switchMenu );
            List<TransUnitTest> tests = DataSetHelper.findTransformationUnitTest( tge.getTransGraph().getTransMeta(), spoon.getMetaStore() );
            for ( final TransUnitTest test : tests ) {
              MenuItem testItem = new MenuItem( switchMenu, SWT.PUSH );
              testItem.setText( test.getName() );
              testItem.addListener( SWT.Selection, event -> switchUnitTest( test, transMeta ) );
            }
            switchItem.setEnabled( !tests.isEmpty() );

            // Locations
            //
            MenuItem locationsItem = new MenuItem( menu, SWT.CASCADE );
            locationsItem.setText( BaseMessages.getString( PKG, "ShowUnitTestMenuExtensionPoint.UnitTest.Locations.Label" ) );

            Menu locationsMenu = new Menu( menu );
            locationsItem.setMenu( locationsMenu );

            if (unitTest!=null) {
              List<TransUnitTestSetLocation> inputLocations = unitTest.getInputDataSets();
              for ( final TransUnitTestSetLocation location : inputLocations ) {
                MenuItem testItem = new MenuItem( locationsMenu, SWT.PUSH );
                testItem.setText( "Input: " + Const.NVL( location.getStepname(), "" ) + " --> " + Const.NVL( location.getDataSetName(), "" ) );
                testItem.addListener( SWT.Selection, event -> editLocation( location, spoon, transMeta ) );
              }

              List<TransUnitTestSetLocation> goldenLocations = unitTest.getGoldenDataSets();
              for ( final TransUnitTestSetLocation location : goldenLocations ) {
                MenuItem testItem = new MenuItem( locationsMenu, SWT.PUSH );
                testItem.setText( "Golden: " + Const.NVL( location.getStepname(), "" ) + " --> " + Const.NVL( location.getDataSetName(), "" ) );
                testItem.addListener( SWT.Selection, event -> editLocation( location, spoon, transMeta ) );
              }
            }

            locationsItem.setEnabled( unitTest!=null );


            new MenuItem( menu, SWT.SEPARATOR );

            MenuItem groupItem = new MenuItem( menu, SWT.CASCADE );
            groupItem.setText( BaseMessages.getString( PKG, "ShowUnitTestMenuExtensionPoint.UnitTest.Groups.Label" ) );
            Menu groupMenu = new Menu( menu );
            groupItem.setMenu( groupMenu );

            // New group
            MenuItem newGroupItem = new MenuItem( groupMenu, SWT.PUSH );
            newGroupItem.setText( BaseMessages.getString( PKG, "ShowUnitTestMenuExtensionPoint.UnitTest.NewGroup.Label" ) );
            newGroupItem.addListener( SWT.Selection, lsGroupNew );

            // edit group
            MenuItem editGroupItem = new MenuItem( groupMenu, SWT.PUSH );
            editGroupItem.setText( BaseMessages.getString( PKG, "ShowUnitTestMenuExtensionPoint.UnitTest.EditGroup.Label" ) );
            editGroupItem.addListener( SWT.Selection, lsGroupEdit );

            new MenuItem( menu, SWT.SEPARATOR );

            MenuItem dataSetItem = new MenuItem( menu, SWT.CASCADE );
            dataSetItem.setText( BaseMessages.getString( PKG, "ShowUnitTestMenuExtensionPoint.UnitTest.DataSet.Label" ) );
            Menu dataSetMenu = new Menu( menu );
            dataSetItem.setMenu( dataSetMenu );

            // New data set
            MenuItem newDataSetItem = new MenuItem( dataSetMenu, SWT.PUSH );
            newDataSetItem.setText( BaseMessages.getString( PKG, "ShowUnitTestMenuExtensionPoint.UnitTest.NewDataSet.Label" ) );
            newDataSetItem.addListener( SWT.Selection, lsDataSetNew );

            // edit data set
            MenuItem editDataSetItem = new MenuItem( dataSetMenu, SWT.PUSH );
            editDataSetItem.setText( BaseMessages.getString( PKG, "ShowUnitTestMenuExtensionPoint.UnitTest.EditDataSet.Label" ) );
            editDataSetItem.addListener( SWT.Selection, lsDataSetEdit );

            new MenuItem( dataSetMenu, SWT.SEPARATOR );

            // delete data set
            MenuItem deleteDataSetItem = new MenuItem( dataSetMenu, SWT.PUSH );
            deleteDataSetItem.setText( BaseMessages.getString( PKG, "ShowUnitTestMenuExtensionPoint.UnitTest.DeleteDataSet.Label" ) );
            deleteDataSetItem.addListener( SWT.Selection, lsDataSetDelete );

            new MenuItem( menu, SWT.SEPARATOR );

            MenuItem testItem = new MenuItem( menu, SWT.CASCADE );
            testItem.setText( BaseMessages.getString( PKG, "ShowUnitTestMenuExtensionPoint.UnitTest.UnitTest.Label" ) );
            Menu testMenu = new Menu( menu );
            testItem.setMenu( testMenu );

            // Open transformation and test
            MenuItem openTransItem = new MenuItem( testMenu, SWT.PUSH );
            openTransItem.setText( BaseMessages.getString( PKG, "ShowUnitTestMenuExtensionPoint.UnitTest.OpenTrans.Label" ) );
            openTransItem.addListener( SWT.Selection, lsOpenTrans );

            new MenuItem( testMenu, SWT.SEPARATOR );

            // delete set
            MenuItem delTestItem = new MenuItem( testMenu, SWT.PUSH );
            delTestItem.setText( BaseMessages.getString( PKG, "ShowUnitTestMenuExtensionPoint.UnitTest.DeleteTest.Label" ) );
            delTestItem.addListener( SWT.Selection, lsDelTest );

            canvas.setMenu( menu );
            Point location = canvas.toDisplay( tge.getEvent().x, tge.getEvent().y );
            menu.setLocation( location );
            menu.setVisible( true );

          }
        }
      }
    }
  }

  private void editLocation( TransUnitTestSetLocation location, Spoon spoon, TransMeta transMeta ) {
    try {
      FactoriesHierarchy hierarchy = new FactoriesHierarchy( spoon.getMetaStore(), spoon.getActiveDatabases() );

      Map<String, RowMetaInterface> stepFieldsMap = new HashMap<>(  );
      for ( StepMeta stepMeta : transMeta.getSteps() ) {
        stepFieldsMap.put(stepMeta.getName(), transMeta.getStepFields( stepMeta ));
      }
      TransUnitTestSetLocationDialog dialog = new TransUnitTestSetLocationDialog( spoon.getShell(), location, hierarchy.getSetFactory().getElements(), stepFieldsMap );
      if (dialog.open()) {
        spoon.refreshGraph();
      }
    } catch(Exception e) {
      new ErrorDialog(spoon.getShell(), "Error", "Error editing dataset location", e);
    }
  }

  protected void newUnitTest( TransMeta transMeta ) {
    Spoon spoon = Spoon.getInstance();
    DataSetHelper.getInstance().createUnitTest( spoon, transMeta );
  }

  protected void editUnitTest( String unitTestName, TransMeta transMeta ) {
    Spoon spoon = Spoon.getInstance();
    DataSetHelper.getInstance().editUnitTest( spoon, transMeta, unitTestName );
  }

  public void deleteUnitTest( String unitTestName ) {
    DataSetHelper.getInstance().deleteUnitTest( unitTestName );
  }

  protected void disableUnitTest( String unitTestName ) {
    DataSetHelper.getInstance().detachUnitTest();
  }

  protected void switchUnitTest( TransUnitTest targetTest, TransMeta transMeta ) {
    DataSetHelper.getInstance().switchUnitTest( targetTest, transMeta );
  }


  /**
   * Find the canvas of TransGraph.  For some reason it's not exposed.
   *
   * @param composite
   * @return Canvas of null if it can't be found.
   */
  private Canvas findCanvas( Composite composite ) {
    for ( Control child : composite.getChildren() ) {
      if ( child instanceof Canvas ) {
        return (Canvas) child;
      }
      if ( child instanceof Composite ) {
        Canvas look = findCanvas( (Composite) child );
        if ( look != null ) {
          return look;
        }
      }
    }

    return null;
  }

}
