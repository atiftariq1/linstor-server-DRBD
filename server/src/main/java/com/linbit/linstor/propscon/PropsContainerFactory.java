package com.linbit.linstor.propscon;

import com.linbit.ImplementationError;
import com.linbit.linstor.dbdrivers.DatabaseException;
import com.linbit.linstor.dbdrivers.interfaces.PropsDatabaseDriver;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.transaction.manager.TransactionMgr;

import javax.inject.Inject;
import javax.inject.Provider;

public class PropsContainerFactory
{
    private final PropsDatabaseDriver dbDriver;
    private final Provider<TransactionMgr> transMgrProvider;

    @Inject
    public PropsContainerFactory(
        PropsDatabaseDriver dbDriverRef,
        Provider<TransactionMgr> transMgrProviderRef
    )
    {
        dbDriver = dbDriverRef;
        transMgrProvider = transMgrProviderRef;
    }

    public PropsContainer getInstance(String instanceName) throws DatabaseException
    {
        PropsContainer container = create(instanceName);

        try
        {
            container.loadAll();
        }
        catch (AccessDeniedException exc)
        {
            throw new ImplementationError("Access denied loading container props", exc);
        }

        return container;
    }

    public PropsContainer create(String instanceName)
    {
        PropsContainer container;
        try
        {
            container = new PropsContainer(null, null, dbDriver, transMgrProvider);
        }
        catch (InvalidKeyException keyExc)
        {
            // If root container creation generates an InvalidKeyException,
            // that is always a bug in the implementation
            throw new ImplementationError(
                "Root container creation generated an exception",
                keyExc
            );
        }
        container.instanceName = instanceName;

        return container;
    }
}
