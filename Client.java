/**
 * Client creates /workers and /tasks znodes; submits 10 tasks, each named
 * /tasks/task-000000000d where d = 0-9; waits until /workers and /tasks have
 * no more children; and deletes these two znodes.
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

public class Client implements Watcher, Closeable {
    private ZooKeeper zk;                       // ZooKeeper to join
    private String hostPort;                    // ZooKeeper's port
    private volatile boolean connected = false; // true if connected to zk
    private volatile boolean expired = false;   // true if session expired

    /**
     * Is the constructor that accepts ZooKeeper's IP addr/port to listen at.
     *
     * @param hostPort IP address:IP port ZooKeeper will listen at
     */
    public Client( String hostPort ) {
	this.hostPort = hostPort;
    }

    /**
     * Joins ZooKeeper session at the port given through the constructor.
     * The session will be expired at 15 seconds for no communication.
     */
    public void startZK( ) throws IOException {
	zk = new ZooKeeper( hostPort, 15000, this );
    }

    /**
     * Implements Watcher.process( )
     */
    public void process( WatchedEvent e ) { 
        System.out.println( e.toString( ) + ", " + hostPort );
        if( e.getType( ) == Event.EventType.None ) {
            switch ( e.getState( ) ) {
            case SyncConnected:
                /*
                 * Registered with ZooKeeper
                 */
                connected = true;
                break;
            case Disconnected:
                connected = false;
                break;
            case Expired:
                expired = true;
                connected = false;
                System.err.println( "Session expired" );
            default:
                break;
            }
        }
    }

    /**
     * Implements Closeable.close( )
     */
    @Override
    public void close( ) 
            throws IOException
    {
        System.out.println( "Closing" );
        try{
            zk.close();
        } catch (InterruptedException e) {
            System.err.println( "ZooKeeper interrupted while closing" );
        }
    }
    
   /**
     * Checks if the client is connected to ZooKeeper
     *
     * @return true if connected
     */
    public boolean isConnected() {
        return connected;
    }
    
    /**
     * Checks if the client's session was expired
     *
     * @return true if expired
     */ 
    public boolean isExpired() {
        return expired;
    }

     /**
     * Is Client's main logic.
     *
     * @param args[] args[0] is Zookeeper's IPaddr:IPport.
     */   
    public static void main( String args[] ) throws Exception {
	// memorize the ZooKeeper port
	Client client = new Client( args[0] );

	// start ZooKeeper
	client.startZK( );

	// wait until connected to ZooKeeper
	System.out.println( "wait for connection" );
	while( !client.isConnected( ) ) {
            Thread.sleep( 100 );
	}
	System.out.println( "connected" );

	client.createWorkerNode( ); // create /workers
	client.createBagOfTasks( ); // create /tasks/task-000000000d (d=0-9)
	client.confirmEmptyBag( );  // delete /workers and /tasks
    }

    private String pid = Long.toHexString( ProcessHandle.current( ).pid( ) );
    private final int nTasksSubmitted = 10;
    private int nTasksCompleted = 0;

    /**
     * Creates the /workers znode synchronously.
     */
    private void createWorkerNode( ) throws Exception {
	zk.create( "/workers",
		   pid.getBytes( ),
		   Ids.OPEN_ACL_UNSAFE, 
		   CreateMode.PERSISTENT );
	System.out.println( "/workers created by client " + pid );
    }

    /**
     * Creates the /tasks znode synchronously and thereafter submits
     * /tasks/task-000000000d where d=0-9. Each task has "submitted" as its
     * data
     */
    private void createBagOfTasks( ) throws Exception {
	zk.create( "/tasks",
		   pid.getBytes( ),
		   Ids.OPEN_ACL_UNSAFE, 
		   CreateMode.PERSISTENT );
	System.out.println( "/tasks created by client " + pid );

	for ( int i = 0; i < nTasksSubmitted; i++ ) {
	    String taskID =
		zk.create( "/tasks/task-",
			   "submitted".getBytes( ),
			   Ids.OPEN_ACL_UNSAFE,
			   CreateMode.PERSISTENT_SEQUENTIAL );
	    System.out.println( taskID + " submitted" ); 
	}
    }

    /**
     * Launches a watcher for each task, confirms all tasks have been deleted,
     * deletes /tasks, checks all workers are gone, and finally deletes 
     * /workers.
     */
    private void confirmEmptyBag( ) throws Exception {
	
	// for each task, launch a task watcher.
	for ( int i = 0; i < nTasksSubmitted; i++ ) {
	    zk.exists( "/tasks/task-000000000" + i,
		       taskWatcher );
	    System.out.println( "/tasks/task-000000000" + i + " under watch" );
	}

	// nTasksCompleted is incremented by each task watcher, so that the
	// main thread waits until nTasksCompleted comes up to nTasksSubmitted.
	while ( nTasksCompleted < nTasksSubmitted )
	    Thread.sleep( 1000 );

	// All tasks have been processed and deleted. So, it's time to delete
	// /tasks.
	System.out.println( "all tasks deleted" );
	zk.delete( "/tasks", 0 );

	// Waits untill all workers disappeared. Then, we can delete /workers.
	while ( true ) {
	    List<String> workers = zk.getChildren( "/workers", false, null );
	    if ( workers == null || workers.size( ) == 0 )
		break;
	}
	System.out.println( "all workers signed off" );
	zk.delete( "/workers", 0 );
    }

    /**
     * Watches any changes of /task and increments nTaskCompleted if the 
     * change was a task-deleting event. Otherwise, this method reschedules
     * a task watcher for detecting future events.
     */
    Watcher taskWatcher = new Watcher( ) {
        public void process( WatchedEvent event ) {
	    System.out.println( event.toString( ) );
            if( event.getType( ) == EventType.NodeDeleted ) {
		nTasksCompleted++;
		System.out.println( "deleted" );
            }
	    else {
		try {
		    zk.exists( event.getPath( ), taskWatcher );
		} catch( Exception exception ) { }
	    }
        }
    };

}
