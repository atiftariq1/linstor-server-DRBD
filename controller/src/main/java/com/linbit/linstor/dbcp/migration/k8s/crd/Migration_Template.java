// package com.linbit.linstor.dbcp.migration.k8s.crd;
//
// @K8sCrdMigration(
// description = "",
// version = -1
// )
// public class Migration_Template extends BaseK8sCrdMigration
// {
// public Migration_Template()
// {
// super(
// GenCrdV_OLD.createSchemaUpdateContext(),
// GenCrdV_NEW.createTxMgrContext(),
// GenCrdV_NEW.createSchemaUpdateContext()
// );
// }
//
// @Override
// public void migrateImpl() throws Exception
// {
// // load data from database that needs to change
//
// // update CRD entries for all DatabaseTables
// updateCrdSchemaForAllTables();
//
// // write modified data to database
// }
// }
