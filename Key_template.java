/**
 * Key is used among Workers to obtain the /lock znode for non-interruptibly
 * accessing the /tasks znode and its children several times.
 */

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.AsyncCallback.ChildrenCallback;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.data.Stat;

public class Key {
    private ZooKeeper zk;                     // ZooKeeper connected to Workers
    private static Object syncObject = null;  // Used to suspend a worker

    /**
     * Is the constructor that accepts a worker's ZooKeeper object and sets up
     * a synchronization object with itself.
     * @param zk_init a calling worker's ZooKeeper object
     */
    public Key( ZooKeeper zk_init ) {
	this.zk = zk_init;
	syncObject = this;
    }

    /////////////////////// Implement all methods below ///////////////////////
    /**
     * This is your homework assignment.
     *
     * Tries to obtain the key on /lock. If not, waits on syncObject (= this).
     */
    public void lock( ) {
	while ( true ) {
	    try {
		String lock = zk.create( "/lock",
				  null,
				  Ids.OPEN_ACL_UNSAFE,
				  CreateMode.EPHEMERAL );
		
		// upon a successful creation of /lock, I got the lock.
		if ( lock != null && lock.equals( "/lock" ) ) {
		    System.out.println( lock + " acquired" );
		    return;
		}
		System.err.println( lock + " error" ); // shouldn't happen
		
	    } catch( KeeperException keeperexception ) {
		// /lock has been already created by someone
		System.err.println( "/lock locked already by someone else" );
		try {
		    // YOUR WORK: call zk.exists to set up lockWtacher( )
		    // YOUR WORK: if it's not null
		    if (this.zk.exists("/lock", this.lockWatcher) != null) {
			// YOUR WORK: sleep here on syncObject
			synchronized (syncObject) {
                            System.out.println("wait on " + syncObject);
                            this.wait();
                        }
			System.out.println( "/lock notified" );
		    }
		    // YOUR WORK: else go back to the top of while( )
		    else {
                        System.out.println("back to the top of while( )");
                    }
		} catch ( Exception another ) {
		    // YOUR WORK: print this exception and go back to the top of while( );
		    System.err.println(another.toString());
		}
	    } catch( Exception others ) { }
	}
    }

    /**
     * This is your homework assignment.
     *
     * Is invoked upon a watcher event: when /lock is deleted.
     */
    Watcher lockWatcher = new Watcher( ) {
	public void process( WatchedEvent event ) {
	    System.out.println( event.toString( ) );
	    if ( event.getType( ) == EventType.NodeDeleted ) {
		// /lock was deleted
		// YOUR WORK: wake up myself who are sleeping on syncObject
		synchronized (Key.syncObject) {
                    Key.syncObject.notify();
                    System.out.println("notify " + Key.syncObject);
                }
		System.out.println( "/lock unlocked informed" );
	    }
	}
    };

    /**
     * This is your hoemwork assignment.
     *
     * Unlocks the key on /lock. Simply deleted "/lock" znode.
     */
    public void unlock( ) {
	try {
	    zk.delete( "/lock", 0 );
	} catch( Exception e ) {
	    System.err.println( e.toString( ) );
	    return;
	}
	System.out.println( "/lock released" );
    }
}
