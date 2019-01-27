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

package org.pentaho.di.dataset.steps.exectests;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs2.FileObject;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.Result;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.row.RowDataUtil;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.dataset.TransUnitTest;
import org.pentaho.di.dataset.UnitTestResult;
import org.pentaho.di.dataset.spoon.DataSetHelper;
import org.pentaho.di.dataset.util.DataSetConst;
import org.pentaho.di.dataset.util.FactoriesHierarchy;
import org.pentaho.di.repository.RepositoryDirectoryInterface;
import org.pentaho.di.repository.StringObjectId;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.metastore.api.exceptions.MetaStoreException;
import org.pentaho.metastore.persist.MetaStoreFactory;

import java.util.ArrayList;
import java.util.List;

public class ExecuteTests extends BaseStep implements StepInterface {

  public ExecuteTests( StepMeta stepMeta, StepDataInterface stepDataInterface, int copyNr, TransMeta transMeta, Trans trans ) {
    super( stepMeta, stepDataInterface, copyNr, transMeta, trans );
  }

  @Override
  public boolean init( StepMetaInterface smi, StepDataInterface sdi ) {
    ExecuteTestsMeta meta = (ExecuteTestsMeta) smi;
    ExecuteTestsData data = (ExecuteTestsData) sdi;

    try {
      data.hierarchy = new FactoriesHierarchy( metaStore, getTransMeta().getDatabases() );

      data.hasPrevious = false;
      StepMeta[] prevSteps = getTransMeta().getPrevSteps( getStepMeta() );
      if ( prevSteps.length > 0 ) {
        data.hasPrevious = true;

        if ( StringUtils.isEmpty( meta.getTestNameInputField() ) ) {
          log.logError( "When this step receives input it wants the name of a field to get the unit test name from to determine which steps to execute" );
          setErrors( 1 );
          return false;
        }
      }
    } catch ( Exception e ) {
      log.logError( "Unable to load information from the metastore", e );
      setErrors( 1 );
      return false;
    }

    return super.init( smi, sdi );
  }

  @Override
  public boolean processRow( StepMetaInterface smi, StepDataInterface sdi ) throws KettleException {
    ExecuteTestsMeta meta = (ExecuteTestsMeta) smi;
    ExecuteTestsData data = (ExecuteTestsData) sdi;

    if ( first ) {
      first = false;

      MetaStoreFactory<TransUnitTest> testFactory = data.hierarchy.getTestFactory();

      // Read all the unit test names from the previous step(s)
      //
      if ( data.hasPrevious ) {

        data.tests = new ArrayList<>();
        Object[] row = getRow();

        if ( row == null ) {
          // No input and as such no tests to execute. We're all done here.
          //
          setOutputDone();
          return false;
        }

        int inputFieldIndex = getInputRowMeta().indexOfValue( meta.getTestNameInputField() );
        if ( inputFieldIndex < 0 ) {
          throw new KettleException( "Unable to find test name field '" + meta.getTestNameInputField() + "' in the input" );
        }

        while ( row != null ) {
          String testName = getInputRowMeta().getString( row, inputFieldIndex );
          try {
            TransUnitTest transUnitTest = testFactory.loadElement( testName );
            data.tests.add( transUnitTest );
          } catch ( Exception e ) {
            throw new KettleException( "Unable to load test '" + testName + "'", e );
          }
          row = getRow();
        }
      } else {
        // Get all the unit tests from the meta store
        // Read them all, filter by type later below...
        //
        try {
          data.tests = new ArrayList<>();
          for ( String testName : testFactory.getElementNames() ) {
            TransUnitTest transUnitTest = testFactory.loadElement( testName );
            if ( meta.getTypeToExecute() == null || meta.getTypeToExecute() == transUnitTest.getType() ) {
              data.tests.add( transUnitTest );
            }
          }
        } catch ( MetaStoreException e ) {
          throw new KettleException( "Unable to read transformation unit tests from the metastore", e );
        }
      }

      data.testsIterator = data.tests.iterator();
      data.outputRowMeta = new RowMeta();
      meta.getFields( data.outputRowMeta, getStepname(), null, null, this, repository, metaStore );
    }

    // Execute one test per iteration.
    //
    if ( data.testsIterator.hasNext() ) {
      TransUnitTest test = data.testsIterator.next();

      // Let's execute this test.
      //
      // 1. Load the transformation meta data, set unit test attributes...
      //
      TransMeta testTransMeta = null;

      try {
        testTransMeta = loadTestTransformation( test );

        // 2. Create the transformation executor...
        //
        if ( log.isDetailed() ) {
          log.logDetailed( "Executing transformation '" + testTransMeta.getName() + "' for unit test '" + test.getName() + "'" );
        }
        Trans testTrans = new Trans( testTransMeta, this );

        // 3. Pass execution details...
        //
        testTrans.setLogLevel( getTrans().getLogLevel() );
        testTrans.setRepository( getTrans().getRepository() );
        testTrans.setMetaStore( getTrans().getMetaStore() );

        // 4. Execute
        //
        testTrans.execute( getTrans().getArguments() );
        testTrans.waitUntilFinished();

        // 5. Validate results...
        //
        Result transResult = testTrans.getResult();
        if ( transResult.getNrErrors() != 0 ) {
          // The transformation had a failure, report this too.
          //
          Object[] row = RowDataUtil.allocateRowData( data.outputRowMeta.size() );
          int index = 0;
          row[ index++ ] = testTransMeta.getName();
          row[ index++ ] = null;
          row[ index++ ] = null;
          row[ index++ ] = null;
          row[ index++ ] = Boolean.TRUE;
          row[ index++ ] = transResult.getLogText();

          putRow( data.outputRowMeta, row );
        }

        List<UnitTestResult> testResults = new ArrayList<UnitTestResult>();
        DataSetConst.validateTransResultAgainstUnitTest( testTrans, test, data.hierarchy, testResults );

        for ( UnitTestResult testResult : testResults ) {
          Object[] row = RowDataUtil.allocateRowData( data.outputRowMeta.size() );
          int index = 0;
          row[ index++ ] = testResult.getTransformationName();
          row[ index++ ] = testResult.getUnitTestName();
          row[ index++ ] = testResult.getDataSetName();
          row[ index++ ] = testResult.getStepName();
          row[ index++ ] = testResult.isError();
          row[ index++ ] = testResult.getComment();

          putRow( data.outputRowMeta, row );
        }

        return true;
      } catch ( KettleException e ) {
        // Some configuration or setup error...
        //
        Object[] row = RowDataUtil.allocateRowData( data.outputRowMeta.size() );
        int index = 0;
        row[ index++ ] = testTransMeta == null ? null : testTransMeta.getName();
        row[ index++ ] = test.getName();
        row[ index++ ] = null;
        row[ index++ ] = null;
        row[ index++ ] = Boolean.TRUE; // ERROR!
        row[ index++ ] = e.getMessage() + " : " + Const.getStackTracker( e );

        putRow( data.outputRowMeta, row );
        return true;
      }
    } else {
      setOutputDone();
      return false;
    }
  }

  private TransMeta loadTestTransformation( TransUnitTest test ) throws KettleException {
    TransMeta unitTestTransMeta = null;
    // Environment substitution is not yet supported in the UI
    //
    String filename = getTrans().environmentSubstitute( test.getTransFilename() );
    if ( StringUtils.isNotEmpty( filename ) ) {

      // Do we need a relative path resolution?
      //
      String basePathString = environmentSubstitute( test.getBasePath() );
      if ( StringUtils.isEmpty( basePathString ) ) {
        // Check global variable
        //
        basePathString = getVariable( DataSetConst.VARIABLE_UNIT_TESTS_BASE_PATH );
      }

      if ( StringUtils.isNotEmpty( basePathString ) ) {

        FileObject basePath = KettleVFS.getFileObject( basePathString );

        // Try to resolve the relative path stored in the test...
        //
        try {
          filename = basePath.resolveFile( filename ).toString();
        } catch ( Exception e ) {
          throw new KettleException( "Unable to resolve relative path of " + filename + " against base path " + basePathString, e );
        }
      }

      unitTestTransMeta = new TransMeta( filename, repository, true, getTrans() );
    } else {
      if ( StringUtils.isNotEmpty( test.getTransObjectId() ) ) {
        if ( repository == null ) {
          throw new KettleException( "No repository available to load transformation from '" + test.getTransRepositoryPath() + "'" );
        } else {
          unitTestTransMeta = repository.loadTransformation( new StringObjectId( test.getTransObjectId() ), null ); // null=last version
        }
      } else if ( StringUtils.isNotEmpty( test.getTransRepositoryPath() ) ) {
        if ( repository == null ) {
          throw new KettleException( "No repository available to load transformation from '" + test.getTransRepositoryPath() + "'" );
        } else {
          String directoryName = DataSetConst.getDirectoryFromPath( test.getTransRepositoryPath() );
          String transName = DataSetConst.getNameFromPath( test.getTransRepositoryPath() );
          RepositoryDirectoryInterface directory = repository.findDirectory( directoryName );
          unitTestTransMeta = repository.loadTransformation( transName, directory, null, true, null );
        }
      }
    }
    if ( unitTestTransMeta == null ) {
      throw new KettleException( "Unable to find a valid file or repository reference for transformation in unit test '" + test.getName() + "'" );
    }

    // Don't show to unit tests results dialog in case of errors
    //
    unitTestTransMeta.setVariable( DataSetConst.VAR_DO_NOT_SHOW_UNIT_TEST_ERRORS, "Y" );

    // Pass some data from the parent...
    //
    unitTestTransMeta.setRepository( repository );
    unitTestTransMeta.setMetaStore( metaStore );
    unitTestTransMeta.copyVariablesFrom( getTrans() );
    unitTestTransMeta.copyParametersFrom( getTrans() );

    // clear and load attributes for unit test...
    //
    DataSetHelper.selectUnitTest( unitTestTransMeta, test );

    // Make sure to run the unit test: gather data to compare after execution.
    //
    unitTestTransMeta.setVariable( DataSetConst.VAR_RUN_UNIT_TEST, "Y" );
    unitTestTransMeta.setVariable( DataSetConst.VAR_UNIT_TEST_NAME, test.getName() );

    return unitTestTransMeta;
  }


}
