package com.splicemachine.constants.bytes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import org.apache.hadoop.hbase.util.Bytes;

/**
 * 
 * This class encapsulates byte[] manipulation with the IR applications.  It relies heavily on HBase's Bytes class.
 * 
 * @author John Leach
 * @version %I%, %G%
 *
 * @see org.apache.hadoop.hbase.util.Bytes
 *
 */
public class BytesUtil {
	/**
	 * 
	 * Method to return bytes based on an object and its corresponding class.
	 * 
	 * @param value
	 * @param instanceClass
	 * @return byte array of the object and its corresponding class
	 */
	public static <T> byte[] toBytes(Object value, Class<T> instanceClass) {
		if (value == null) return null;
		if ((instanceClass == Boolean.class) || (instanceClass == boolean.class))
			return Bytes.toBytes((Boolean)value);
		else if ((instanceClass == Byte.class) || (instanceClass == byte.class))
			return Bytes.toBytes((Byte)value); 
		else if ((instanceClass == Character.class) || (instanceClass == char.class))
			return Bytes.toBytes((Character)value); 
		else if ((instanceClass == Double.class) || (instanceClass == double.class))
			return Bytes.toBytes((Double)value); 
		else if ((instanceClass == Float.class) || (instanceClass == float.class))
			return Bytes.toBytes((Float)value); 
		else if ((instanceClass == Integer.class) || (instanceClass == int.class))			
			return Bytes.toBytes((Integer)value); 
		else if ((instanceClass == Long.class) || (instanceClass == long.class))
			return Bytes.toBytes((Long)value); 
		else if ((instanceClass == Short.class) || (instanceClass == short.class))
			return Bytes.toBytes((Short)value); 
		else if (instanceClass == Date.class)
			return Bytes.toBytes(((Date)value).getTime());
		else if (instanceClass == Calendar.class)
			return Bytes.toBytes(((Calendar)value).getTime().getTime());
		else if (instanceClass == String.class) {
			return Bytes.toBytes((String)value);
		}
		else if (value instanceof Calendar)
			return Bytes.toBytes(((Calendar) value).getTimeInMillis());
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			ObjectOutputStream oos = new ObjectOutputStream(bos);
			oos.writeObject(value);

			byte[] Out = bos.toByteArray();
			oos.close();
			bos.close();
			return Out;
		}
		catch (IOException e) {
			throw new RuntimeException(e.getMessage(), e);
		}
	}
	/**
	 * 
	 * Returns an object from its byte array representation.
	 * 
	 * @param value
	 * @param valueType
	 * @return the object converted from bytes array
	 */
	@SuppressWarnings("rawtypes")
	public static Object fromBytes(byte[] value, Class valueType) {
		if (value == null) return null;

		if ((valueType == Boolean.class) || (valueType == boolean.class))
			return Bytes.toBoolean(value);
		else if ((valueType == Byte.class) || (valueType == byte.class)) {
			byte out;
			try {
				ByteArrayInputStream bis = new ByteArrayInputStream(value);
				ObjectInputStream ois = new ObjectInputStream(bis);
				out = ois.readByte();
				ois.close();
				bis.close();
			}
			catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
			return out;
		} 
		else if ((valueType == Character.class) || (valueType == char.class)) {
			char out;
			try {
				ByteArrayInputStream bis = new ByteArrayInputStream(value);
				ObjectInputStream ois = new ObjectInputStream(bis);
				out = ois.readChar();
				ois.close();
				bis.close();
			}
			catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
			return out;    
		} 
		else if ((valueType == Double.class) || (valueType == double.class))
			return Bytes.toDouble(value); 
		else if ((valueType == Float.class) || (valueType == float.class))
			return Bytes.toFloat(value); 
		else if ((valueType == Integer.class) || (valueType == int.class))
			return Bytes.toInt(value); 
		else if ((valueType == Long.class) || (valueType == long.class))
			return Bytes.toLong(value); 
		else if ((valueType == Short.class) || (valueType == short.class))
			return Bytes.toShort(value); 
		else if (valueType == String.class)
			return Bytes.toString(value); 
		else if (valueType == Calendar.class || valueType == GregorianCalendar.class) {
			Long timeInMillis = Bytes.toLong(value);
			GregorianCalendar gc = new GregorianCalendar();
			gc.setTimeInMillis(timeInMillis);
			return gc;
		}
		else if (valueType == Date.class) {
			Long timeInMillis = Bytes.toLong(value);
			GregorianCalendar gc = new GregorianCalendar();
			gc.setTimeInMillis(timeInMillis);
			return gc.getTime();
		}

		
		Object out;
		try {
			ByteArrayInputStream bis = new ByteArrayInputStream(value);
			ObjectInputStream ois = new ObjectInputStream(bis);
			out = ois.readObject();
			ois.close();
			bis.close();
		}
		catch (IOException e) {
			throw new RuntimeException(e.getMessage(), e);
		}
		catch (ClassNotFoundException e) {
			throw new RuntimeException(e.getMessage(), e);
		}
		return out;
	}
	/**
	 * 
	 * Retrieves the Typed Object from its byte[] representation.
	 * 
	 * @param value
	 * @param valueType
	 * @return the type
	 */
	@SuppressWarnings("unchecked")
	public static <T> T fromBytesToType(byte[] value, Class<T> valueType) {
		return (T) fromBytes(value, valueType);
	}

	/**
	 * Concats a list of byte[].  
	 * 
	 * @param list
	 * @return the result byte array 
	 */
	
	public static byte[] concat(List<byte[]> list) {
        int length = 0;
        for (byte[] bytes : list) {
            length += bytes.length;
        }
        byte[] result = new byte[length];
        int pos = 0;
        for (byte[] bytes : list) {
            System.arraycopy(bytes, 0, result, pos, bytes.length);
            pos += bytes.length;
        }
        return result;
    }
	/**
	 * 
	 * Increments a byte[]
	 * 
	 * @param array
	 * @param index
	 */
	public static void incrementAtIndex(byte[] array, int index) {
          if (array[index] == Byte.MAX_VALUE) {
              array[index] = 0;
              if(index > 0)
                  incrementAtIndex(array, index - 1);
          }
          else {
              array[index]++;
          }
      }
	
	public static void decrementAtIndex(byte[] array,int index) {
		if(array[index] == Byte.MIN_VALUE){
			array[index] = Byte.MAX_VALUE;
			if(index >0)
				decrementAtIndex(array,index-1);
		}else{
			array[index]--;
		}
	}

    public static byte[] copyAndIncrement(byte[] start) {
        byte[] other = new byte[start.length];
        System.arraycopy(start,0,other,0,start.length);
        incrementAtIndex(other,other.length-1);
        return other;
    }
}
